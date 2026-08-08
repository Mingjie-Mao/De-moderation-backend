[![English](https://img.shields.io/badge/English-1f6feb?style=for-the-badge)](README.md)
[![中文](https://img.shields.io/badge/%E4%B8%AD%E6%96%87-grey?style=for-the-badge)](README.zh-CN.md)

# CampusGuard

A content-moderation backend for a university forum. Members report content, an
engine judges it, an administrator decides what happens. **The engine never acts
on its own.**

On 192 labelled samples, the same harness scores every engine:

| engine | macro-F1 | ALLOW recall | REMOVE recall | ESCALATE recall | p50 | tokens/sample |
|---|---|---|---|---|---|---|
| `keyword-v1` — term list | 0.286 | 1.000 | 0.106 | 0.000 | 0.05 ms | — |
| `gemini-3.5-flash-lite/v1` | 0.617 | 0.978 | 0.939 | 0.056 | 906 ms | 341 |
| `gemini-3.5-flash-lite/v2` | **0.924** | 0.989 | 0.970 | **0.778** | 868 ms | 651 |

v2 is the same model with a rewritten prompt. No code changed.
[What that means, and what it cost →](#evaluation)

Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · Spring Security (JWT) ·
Spring AI (Gemini) · Resilience4j · Testcontainers · Docker Compose

[Architecture](docs/architecture.md) · [Evaluation report](docs/evaluation.md) ·
[Demo script](docs/demo-script.md)

## What it does

| | |
|---|---|
| **Forum API** | posts, threaded comments, cursor-paged feed and threads, JWT authentication |
| **Reporting** | many reports on one target collapse into one case, and one engine call |
| **Durable queue** | `SELECT ... FOR UPDATE SKIP LOCKED`; a case survives the worker that claimed it dying |
| **Pluggable engines** | rule matching and an LLM behind one interface, addressable by name |
| **Degradation** | any model failure falls back to rules; the queue never stops |
| **Evaluation harness** | every engine scored on one labelled dataset, with per-sample disagreement analysis |
| **Audit log** | every transition, verdict and decision, append-only |

Two constraints shaped all of it. **No engine ever removes content** — it
produces a recommendation, a confidence and a rationale a person can read, and
the case waits, because an automated system that takes content down on its own
is one nobody can appeal to. And **the queue keeps moving when the model does
not**: a missing key, a timeout, a rate limit or an answer that fails validation
all degrade to rule matching, so running with no model configured is an ordinary
configuration rather than an error.

## Structure

```
src/main/java/com/campusguard/        7.7k lines · 119 files
├── auth/          register, log in, issue tokens
├── security/      JWT, per-request account revalidation, route rules
├── user/          accounts, roles, suspension
├── post/          posts and the cursor-paged feed
├── comment/       threaded comments, depth-bounded and paged
├── report/        filing a report, its own rate limit
├── moderation/    the core — 47 files
│   ├── (root)     case state machine, worker, stalled-case sweep
│   ├── admin/     the administrator console API
│   ├── rule/      the rule set and its provider
│   └── engine/    ModerationEngine seam, registry, keyword engine
│       └── ai/    Gemini engine, prompt versions, resilience, invocation log
├── evaluation/    the benchmark harness — 18 files
├── audit/         append-only log
└── common/        problem-detail errors, authoring rate limits, shared types

src/test/java/                        4.1k lines · 32 files · 157 tests
src/main/resources/db/migration/       V1–V7, Flyway-owned
docs/                                  architecture, evaluation report, demo script
```

The moderation package is the largest because it is the point. Everything else
exists so that it has something to moderate.

## The workflow

```
report ──┬─> moderation case (QUEUED)
         v
      worker claims a batch          SKIP LOCKED: a second instance takes
         │                           different rows, not a place in line
         v
      ANALYSING ──> engine ──> AWAITING_REVIEW
         │            │
         │            └─ fails ──> rule engine ──> AWAITING_REVIEW
         │                          (degraded, and recorded as such)
         v
      administrator decides ──> RESOLVED   NONE | HIDE | DELETE | BAN
```

A case claimed by a worker that then died goes back on the queue — that is what
makes it durable rather than merely asynchronous.

## Running locally

Requires JDK 21 and a Docker-compatible container runtime.

```bash
cp .env.example .env
```

Fill in `DB_PASSWORD` and generate `JWT_SECRET` with `openssl rand -hex 32`. The
application refuses to start without one: a token signed with a publicly known
key is not authentication.

```bash
docker compose up -d
```

Compose reads `.env` on its own; Spring Boot does not, so export it too:

```bash
set -a && . ./.env && set +a && mvn spring-boot:run
```

Swagger UI at <http://localhost:8080/swagger-ui.html> is the console, moderation
included — administrators are a handful of people doing a low-volume task, and a
bespoke front end would be a second application to build and secure for no
benefit they would notice.

| endpoint | who |
|---|---|
| `POST /api/auth/register` · `/login` | public |
| `GET /api/posts` · `/{id}` · `/{id}/comments` | public |
| `POST /api/posts` · `/{id}/comments` · `/api/reports` | any signed-in member |
| `DELETE /api/posts/{id}` | the author, or an administrator |
| `GET /api/reports/{id}` | the reporter, or an administrator |
| `GET\|POST /api/admin/moderation-cases/**` | administrators only |

No endpoint grants the administrator role; it is set directly in the database.
An API that hands out privilege on request hands it to whoever asks.

The feed pages by cursor, not offset — new rows arrive at the top, so with an
offset every insertion shifts the page and a reader sees some posts twice and
never sees others. Threads page the same way, by top-level comment: a reply
cannot be rendered without the comment it answers, so a page is a whole
conversation and nesting is bounded separately, by a check constraint.

## AI-assisted moderation

Off unless configured. With no `AI_CHAT_MODEL` there is no model engine at all
and everything above still works.

```bash
printf 'AI_CHAT_MODEL=google-genai\nGEMINI_MODELS=gemini-3.5-flash-lite\nMODERATION_ENGINE=gemini-3.5-flash-lite/v2\nGEMINI_API_KEY=...\n' >> .env
```

An engine is named `model/prompt-version`, and both halves are registered: every
model is paired with every prompt version on the classpath. That is what makes
"the newer wording is better" a row in a table rather than an opinion.

What surrounds the call is the part worth reading:

- **A narrow port.** Everything vendor-specific sits in one class behind a
  three-method interface, so a stub can hang, throw or lie with no network or key.
- **Validation, not trust.** An out-of-range confidence, an unknown decision or a
  non-existent rule code are rejected and fed back as one corrective retry. Load-
  bearing: on the v2 run it caught eleven malformed answers, all eleven correct on
  the second ask.
- **Timeout, then circuit.** A timeout alone spends the full budget on every
  request while a provider is down; the circuit makes failure instant, so the
  queue degrades at full speed.
- **Backoff only where it helps.** Rate limits get exponential backoff outside the
  call budget. A refused credential does not — it will still be refused in two
  seconds.
- **Every call recorded.** `ai_invocations` holds model, prompt version, tokens,
  latency, status and raw response, successes and failures alike.

## Evaluation

```bash
set -a && . ./.env && set +a && mvn spring-boot:run \
  -Dspring-boot.run.arguments="--campusguard.evaluation.run=true \
  --campusguard.evaluation.dataset=docs/evaluation-samples.json"
```

Runs every engine over the same labelled set and writes
[`docs/evaluation.md`](docs/evaluation.md) to read, `evaluation.json` to diff
against the next run, and `evaluation-samples.csv` with every answer. An
unavailable engine is reported and skipped rather than ending the run.

Two separate results, and the sample-level accounting says which is which.
Every one of the 192 samples, by who got it right:

| | count | what it means |
|---|---|---|
| term list already right | 97 | mostly ordinary content, correctly left alone |
| **only the model got it** | **57** | what a term list structurally cannot do |
| **only the rewritten prompt got it** | **28** | what the model could do and was not being asked to |
| still wrong | 10 | the remaining work |

**The 57 are abuse in words no list contains**, in both languages:

```
s093  expect REMOVE   rules ALLOW    v1 REMOVE   v2 REMOVE
      "You contribute nothing to this group and everyone in the tutorial knows it."
s105  expect REMOVE   rules ALLOW    v1 REMOVE   v2 REMOVE
      "楼主脑子进水了吧，说的都是什么垃圾东西。"
```

**The 28 are the interesting ones, because nothing changed but the wording.**
v1's error was almost entirely one behaviour — ESCALATE recall 0.056, thirty-three
of thirty-six samples that should reach a person answered ALLOW:

```
s159  expect ESCALATE  rules REMOVE   v1 ALLOW    v2 ESCALATE
      "Someone replied to my post with 'kill yourself'. Should I report it?"
s161  expect ESCALATE  rules ALLOW    v1 ALLOW    v2 ESCALATE
      "That two hour lecture was so bad I wanted to die."
s176  expect ESCALATE  rules ALLOW    v1 ALLOW    v2 ESCALATE
      "有人在评论区骂我，我截图了，应该发给谁处理？"
```

Its own rationales said why, and it was not misreading anything. Asked whether a
post breaks a rule it answered correctly — a student asking who to report
harassment to is not harassing anyone. But the queue is asking whether a machine
should be the one to close the matter, and those two questions come apart exactly
on the cases a moderation queue exists for.

So v2 defines the three answers by what happens next rather than by what the text
is, and keeps an explicit floor under REMOVE, because the cheapest way to raise
ESCALATE recall is to escalate everything. ESCALATE recall reaches 0.778 with
ALLOW and REMOVE unmoved.

The costs, in full: roughly twice the prompt tokens on every call forever
(341 → 651), and forty answers across the run that came back with a rule code
carrying its severity — `"ABUSE (HIGH)"` — which the validator rejected and the
corrective retry fixed, at one extra call each.

### By language

122 English samples to 70 Chinese, scored separately from `evaluation-samples.csv`:

| | n | accuracy | ALLOW | REMOVE | ESCALATE |
|---|---|---|---|---|---|
| English | 122 | 0.926 | 58/59 | 38/39 | 17/24 |
| Chinese | 70 | 0.971 | 31/31 | 26/27 | 11/12 |

The model is not the weaker half in Chinese; twelve Chinese ESCALATE samples is
too few to lean on, but 31/31 and 26/27 are not. The term list is equally poor in
both (REMOVE recall 0.103 and 0.111) — a rule engine only works in the language
you wrote the rules for, and a second language means a second term list to write
and keep writing.

### Three limits

- **v2's score is optimistic by an unknown amount.** Its wording was written after
  reading v1's mistakes on this dataset, so 0.924 is a diagnosis confirmed on the
  data that produced it, not a held-out result. What it does establish is that the
  diagnosis was right: the change targeted one class and that class is what moved.
- **No part of the dataset is real traffic.** The benign half is seed content
  lifted from a campus forum app, where it exists to make a demo look inhabited —
  the right register and the right two languages, written before this system
  existed and so not shaped to suit it, but written by somebody all the same. The
  violating half was written for this evaluation, by someone who had read the rule
  list. Provenance and label are then almost perfectly correlated, and the report
  says so rather than presenting the per-source gap as a finding.
- **The same prompt does not score the same twice.** All three engines are one
  run now, and v1 came back 0.617 where an earlier identical run gave 0.636 —
  same code, same dataset, `temperature: 0.0`. Two points of drift is small
  against a 0.31 gap, and it is a reason not to read a third decimal place
  anywhere in this file, or to trust any comparison thinner than a few points
  without running it more than once.

## Tests

```bash
mvn verify
```

157 tests. Integration tests start their own PostgreSQL through Testcontainers,
so the Compose stack need not be running. A real database rather than an
in-memory substitute, because this schema's correctness lives in partial indexes,
check constraints and unique indexes an in-memory engine does not enforce —
including the one that collapses concurrent reports into a single case, covered
by a test that fires two at once.

The stalled-case sweep is tested in both directions: it must return a case whose
worker died, and must not touch one somebody is still working on. Getting the
second wrong means the same content judged twice and billed twice.

## Some decisions worth the words

**No RAG.** A few dozen rules fit in a prompt with room to spare. Retrieval would
add a vector store, an embedding pipeline and a relevance failure mode in
exchange for nothing. `RuleProvider` is an interface so that changes when the
rule set does.

**Flyway owns the schema, `ddl-auto: validate`, `open-in-view: false`.** An
entity that disagrees with a migration fails at startup rather than silently
mutating the database — it has caught real drift more than once. And with the
session closed before rendering, an unfetched association fails loudly instead of
turning a feed into one query per row.

**A ban takes effect on the next request.** A signed token says what was true
when it was issued; for most APIs that is close enough, but banning is the
strongest thing this console does and it is aimed at somebody actively causing
harm. Measured before the fix: a suspended account's pre-ban token created a
post and got 201. Every authenticated request now re-reads the account and
rebuilds authorities from the stored role, at the cost of one primary-key
lookup — which also means a demoted administrator loses the console at once
rather than an hour later.

**Actuator answers strangers with one word.** `/actuator/health` stays public so
an orchestrator with no credential can still tell the instance is alive, but the
details are administrator-only: on the default of `always`, an anonymous GET
returns the deployment's absolute filesystem path, its disk capacity and the
database engine. So is the rest of `/actuator` — the framework's default of "any
authenticated user" means anyone who signed up a minute ago.

**Authoring is rate limited, not just reporting.** Reports were capped from the
first version and posts were not, which had it backwards: a report costs a
moderator one glance, a post costs an engine call and a queue slot as soon as
anyone flags it. Registration is open, so being signed in stopped nobody.

**Reply nesting is capped in the schema, not in the reader.** Threads are
assembled by recursing once per level, and nothing limited how deep a reply could
go — a chain of eight thousand answered the public comments endpoint with a
StackOverflowError, reachable by one account replying to itself. A check
constraint binds every writer, including an import or a second service, where a
guard in one method would only bind callers who went through it.

## Data attribution

The benign half of the evaluation dataset is seed content taken verbatim from
[De-discussion](https://github.com/Mingjie-Mao/De-discussion), an earlier ANU team
campus-forum app. It was written to populate that app's demo, not harvested from
real users, and it is used here as data only — none of that project's code is in
this repository.
