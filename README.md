[![English](https://img.shields.io/badge/English-1f6feb?style=for-the-badge)](README.md)
[![中文](https://img.shields.io/badge/%E4%B8%AD%E6%96%87-grey?style=for-the-badge)](README.zh-CN.md)

# CampusGuard

A content-moderation backend for a university forum. Members report content, an
engine judges it, an administrator decides what happens. **The engine never acts
on its own.**

On 192 labelled samples, the same harness scores every engine:

| engine | macro-F1 | ALLOW recall | REMOVE recall | ESCALATE recall | p50 | tokens/sample |
|---|---|---|---|---|---|---|
| `keyword-v1` — term list | 0.286 | 1.000 | 0.106 | 0.000 | 0.3 ms | — |
| `gemini-3.5-flash-lite/v1` | 0.636 | 0.989 | 0.970 | 0.056 | 871 ms | 336 |
| `gemini-3.5-flash-lite/v2` | **0.924** | 0.989 | 0.970 | **0.778** | 858 ms | 655 |

v2 is the same model with a rewritten prompt. No code changed.
[What that means, and what it cost →](#evaluation)

Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · Spring Security (JWT) ·
Spring AI (Gemini) · Resilience4j · Testcontainers · Docker Compose

[Architecture](docs/architecture.md) · [Evaluation report](docs/evaluation.md) ·
[Demo script](docs/demo-script.md)

## What it does

| | |
|---|---|
| **Forum API** | posts, comments, cursor-paged feed, JWT authentication |
| **Reporting** | many reports on one target collapse into one case, and one engine call |
| **Durable queue** | `SELECT ... FOR UPDATE SKIP LOCKED`; a case survives the worker that claimed it dying |
| **Pluggable engines** | rule matching and an LLM behind one interface, addressable by name |
| **Degradation** | any model failure falls back to rules; the queue never stops |
| **Evaluation harness** | every engine scored on one labelled dataset, with per-sample disagreement analysis |
| **Audit log** | every transition, verdict and decision, append-only |

## Two constraints that shaped everything

**No engine ever removes content.** It produces a recommendation, a confidence
and a rationale a person can read, and the case waits. An automated system that
takes content down on its own is one nobody can appeal to.

**The queue keeps moving when the model does not.** A missing API key, a timeout,
a rate limit or an answer that fails validation all degrade to rule matching.
Not configuring a model at all is an ordinary configuration, not an error.

## Structure

```
src/main/java/com/campusguard/        7.3k lines · 115 files
├── auth/          register, log in, issue tokens
├── security/      JWT filter, principal resolution, method-level rules
├── user/          accounts, roles, suspension
├── post/          posts and the cursor-paged feed
├── comment/       comments
├── report/        filing a report, per-user rate limit
├── moderation/    the core — 47 files
│   ├── (root)     case state machine, worker, stalled-case sweep
│   ├── admin/     the administrator console API
│   ├── rule/      the rule set and its provider
│   └── engine/    ModerationEngine seam, registry, keyword engine
│       └── ai/    Gemini engine, prompt versions, resilience, invocation log
├── evaluation/    the benchmark harness — 18 files
├── audit/         append-only log
└── common/        problem-detail errors, shared types

src/test/java/                        3.5k lines · 27 files · 130 tests
src/main/resources/db/migration/       V1–V6, Flyway-owned
docs/                                  architecture, evaluation report, demo script
```

The moderation package is the largest because it is the point. Everything else
exists so that it has something to moderate.

## The workflow

```
report ──┬─> moderation case (QUEUED)      several reports on one target
         │                                  become one case, one engine call
         v
      worker claims a batch                 SKIP LOCKED, so a second instance
         │                                  takes different rows rather than
         v                                  queueing behind them
      ANALYSING ──> engine ──> AWAITING_REVIEW
         │            │
         │            └─ fails ──> rule engine ──> AWAITING_REVIEW
         │                          (degraded, and recorded as such)
         v
      administrator decides ──> RESOLVED   NONE | HIDE | DELETE | BAN
```

A case claimed by a worker that then died is returned to the queue. That is what
makes the queue durable rather than merely asynchronous.

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
never sees others.

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
- **Validation, not trust.** Confidence outside `[0,1]`, an unknown decision or a
  non-existent rule code are rejected and fed back as one corrective retry. It is
  load-bearing: on the v2 run it caught eleven malformed answers, all eleven
  correct on the second ask.
- **Timeout, then circuit.** A timeout alone spends the full budget on every
  request while a provider is down; the circuit makes failure instant so the
  queue degrades at full speed.
- **Backoff only where it helps.** Rate limits are retried with exponential
  backoff outside the call budget. A refused credential is not — it will still be
  refused in two seconds.
- **Every call recorded.** `ai_invocations` holds model, prompt version, tokens,
  latency, status and raw response, for successes and failures alike.

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

The table at the top is two separate results.

**The model beats the rules where rules cannot be patched.** Fifty-nine samples
the term list missed, v1 gets right — abuse phrased in words no term list
contains, in both languages the forum is written in.

**The prompt beats the model.** v1's remaining error was almost entirely one
behaviour: ESCALATE recall 0.056, thirty-three of thirty-six samples that should
reach a person answered ALLOW. Its own rationales said why — asked whether a post
breaks a rule it answered correctly, when the queue is asking whether a machine
should be the one to close the matter. "Someone is posting my photo without
permission, what do I do?" breaks no rule and still needs a human.

v2 defines the three answers by what happens next rather than by what the text
is, and keeps an explicit floor under REMOVE, because the cheapest way to raise
ESCALATE recall is to escalate everything. ESCALATE recall reaches 0.778 with
ALLOW and REMOVE unmoved. It cost twice the prompt tokens per call, three
over-escalated samples, and eleven malformed answers that had to be asked again.

Three limits, stated rather than left to be discovered:

- **v2's score is optimistic by an unknown amount.** Its wording was written after
  reading v1's mistakes on this dataset, so 0.924 is a diagnosis confirmed on the
  data that produced it, not a held-out result. What it does establish is that the
  diagnosis was right: the change targeted one class and that class is what moved.
- **The violating half of the dataset was written for it.** The benign half is
  real forum content; a seeded demo application has no abuse to sample. Provenance
  and label are then almost perfectly correlated, and the report says so rather
  than presenting the per-source gap as a finding.
- **The three engines were measured in two runs.** The free tier allows 500
  requests a day; three engines over 192 samples needs 588. v1's row is the
  previous run, in git history, same dataset and same code.

## Tests

```bash
mvn verify
```

130 tests. Integration tests start their own PostgreSQL through Testcontainers,
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

**Schema owned by Flyway, `ddl-auto: validate`.** An entity that disagrees with a
migration fails at startup instead of silently mutating the database. It has
caught real drift more than once.

**`open-in-view: false`.** With it on, lazy-loading problems hide; with it off, an
unfetched association fails loudly rather than turning a feed into one query per
row.

## Data attribution

The benign half of the evaluation dataset is seeded forum content taken verbatim
from an earlier ANU team project,
[De-discussion](https://github.com/Mingjie-Mao/De-discussion). It is used as data
only; none of that project's code is in this repository.
