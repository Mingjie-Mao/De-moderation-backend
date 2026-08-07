# CampusGuard

A content-moderation backend for a university discussion forum: members report
posts and comments, an engine judges what was reported, and an administrator
decides what actually happens.

Two constraints shape the whole design.

**No engine ever removes content.** It produces a recommendation, a confidence
and a rationale a person can read, and the case waits. An automated system that
takes content down on its own is one nobody can appeal to.

**The queue keeps moving when the model does not.** A missing API key, a
timeout, a rate limit or an answer that fails validation all degrade to rule
matching rather than stopping moderation. Not configuring a model at all is an
ordinary configuration, not an error.

Every engine is scored on one labelled dataset by one harness, so choosing
between them is reading a table. On 192 samples, term matching reaches macro-F1
0.286, the model reaches 0.636, and rewording the prompt — no code, no model
change — reaches 0.924. [How, and what it cost.](#evaluation)

## Stack

Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · Spring Security (JWT) ·
Spring AI (Gemini) · Resilience4j · Testcontainers · Docker Compose

[Architecture](docs/architecture.md) · [Evaluation](docs/evaluation.md) ·
[Demo script](docs/demo-script.md)

## The moderation workflow

```
report ──┬─> moderation case (QUEUED)      several reports on one target
         │                                  become one case, and one engine call
         v
      worker claims a batch                 SELECT ... FOR UPDATE SKIP LOCKED
         │                                  so a second instance takes different
         v                                  rows instead of queueing behind them
      ANALYSING ──> engine ──> AWAITING_REVIEW
         │            │
         │            └─ fails ──> rule engine ──> AWAITING_REVIEW
         │                          (degraded, and recorded as such)
         v
      administrator decides ──> RESOLVED   NONE | HIDE | DELETE | BAN
```

Every transition, every verdict and every decision is written to an append-only
audit log. A case claimed by a worker that then died is returned to the queue,
which is what makes the queue durable rather than merely asynchronous.

## Running locally

Requires JDK 21 and a Docker-compatible container runtime.

```bash
cp .env.example .env
```

Fill in `DB_PASSWORD` and generate a signing key for `JWT_SECRET`. The
application refuses to start without one rather than falling back to a default,
because a token signed with a publicly known key is not authentication:

```bash
openssl rand -hex 32
```

```bash
docker compose up -d
```

Docker Compose reads `.env` on its own, but Spring Boot does not, so the same
values have to be exported into the shell that runs the application:

```bash
set -a && . ./.env && set +a && mvn spring-boot:run
```

```bash
curl http://localhost:8080/actuator/health
```

## Trying the API

Swagger UI at <http://localhost:8080/swagger-ui.html> is the primary console,
including for moderation: administrators are a handful of people doing a
low-volume task, and a bespoke front end would be a second application to build
and secure for no benefit they would notice.

Register or log in under **Authentication**, then paste the returned
`accessToken` into **Authorize**.

| | |
|---|---|
| `POST /api/auth/register` · `POST /api/auth/login` | public |
| `GET /api/posts` · `GET /api/posts/{id}` · `GET /api/posts/{id}/comments` | public |
| `POST /api/posts` · `POST /api/posts/{id}/comments` · `POST /api/reports` | any signed-in member |
| `DELETE /api/posts/{id}` | the post's author, or an administrator |
| `GET /api/reports/{id}` | the report's author, or an administrator |
| `GET|POST /api/admin/moderation-cases/**` | administrators only |

There is no endpoint that grants the administrator role; it is set directly in
the database. An API that hands out privilege on request hands it to whoever
asks.

The feed pages by cursor rather than by offset:

```bash
curl 'http://localhost:8080/api/posts?forum=anu-general&size=20'
# -> { "items": [...], "hasMore": true, "nextCursor": "MjAyNi0..." }
```

A forum feed has new rows arriving at the top, so with an offset every insertion
shifts everything down and a reader paging through sees some posts twice and
never sees others. `hasMore` comes from reading one row past the page rather
than from counting the forum.

## AI-assisted moderation

Off unless configured. With no `AI_CHAT_MODEL` set there is no model engine at
all, the registry finds only rule matching, and everything above still works.

```bash
printf 'AI_CHAT_MODEL=google-genai\nGEMINI_MODELS=gemini-3.5-flash-lite\nMODERATION_ENGINE=gemini-3.5-flash-lite/v2\nGEMINI_API_KEY=...\n' >> .env
```

An engine's name is `model/prompt-version`, and both halves are registered: each
model in `GEMINI_MODELS` is paired with each prompt version on the classpath.
That is what makes "the newer wording is better" a row in a table rather than an
opinion — the two versions differ in nothing else, so the comparison isolates
the text. `MODERATION_ENGINE` then picks which of them judges live traffic.

What surrounds the call is the part worth reading:

- **A narrow port.** Everything vendor-specific lives in one class behind a
  three-method interface. That is what makes the resilience layer testable — a
  stub can hang, throw or lie without a network or a key.
- **Validation, not trust.** A response that parses is not a response that is
  correct. Confidence outside `[0,1]`, a decision that is not one of the three,
  or a rule code that does not exist are all rejected, and the complaint is fed
  back to the model as one corrective retry. It is load-bearing: on the v2 run it
  caught eleven answers whose rule code carried the severity along with it, and
  all eleven came back correct on the second ask.
- **Timeout, then circuit.** A timeout alone still spends the full budget on
  every request while a provider is down; the circuit turns a thirty-second
  failure into an instant one so the queue degrades at full speed.
- **Backoff only where it helps.** Rate limiting is retried with exponential
  backoff and jitter, outside the call budget. A refused credential is not: it
  will still be refused in two seconds.
- **Every call recorded.** `ai_invocations` holds model, prompt version, tokens,
  latency, status and the raw response, for successes and failures alike. The
  failure rate is what says whether the fallback is load-bearing or decorative.

## Evaluation

```bash
set -a && . ./.env && set +a && mvn spring-boot:run \
  -Dspring-boot.run.arguments="--campusguard.evaluation.run=true \
  --campusguard.evaluation.dataset=docs/evaluation-samples.json"
```

Runs every registered engine over the same labelled set and writes
[`docs/evaluation.md`](docs/evaluation.md) to read, `evaluation.json` to diff
against the next run, and `evaluation-samples.csv` with every engine's answer to
every sample. An engine that is unavailable is reported and skipped rather than
ending the run.

The rule baseline exists so that a model's score means something. On 192 samples:

| engine | macro-F1 | ALLOW recall | REMOVE recall | ESCALATE recall | p50 | tokens/sample |
|---|---|---|---|---|---|---|
| `keyword-v1` | 0.286 | 1.000 | 0.106 | 0.000 | 0.3 ms | — |
| `gemini-3.5-flash-lite/v1` | 0.636 | 0.989 | 0.970 | 0.056 | 871 ms | 336 |
| `gemini-3.5-flash-lite/v2` | **0.924** | 0.989 | 0.970 | **0.778** | 858 ms | 655 |

Two separate results, and the second is the one worth explaining.

**The model beats the rules where rules cannot be patched.** Fifty-nine samples
the term list missed, v1 gets right: abuse phrased in words no term list
contains, in both languages the forum is written in. That is what a thousandfold
increase in latency buys.

**The prompt beats the model.** v1's remaining error was almost entirely one
behaviour — ESCALATE recall 0.056, thirty-three of thirty-six samples that
should reach a person answered ALLOW. Reading its rationales said why: asked
whether a post breaks a rule it answered correctly, when the question the queue
is actually asking is whether a machine should be the one to close the matter.
"Someone is posting my photo without permission, what do I do?" breaks no rule
and still needs a human.

v2 changes no code and no model. It defines the three answers by what happens
next rather than by what the text is, names the situations where reading
correctly still is not enough, and — the part that mattered — keeps an explicit
floor under REMOVE, because the cheapest way to raise ESCALATE recall is to
escalate everything. ESCALATE recall goes to 0.778 with ALLOW and REMOVE
unmoved.

The costs are in the table and in the report: roughly twice the prompt tokens on
every call forever, three samples over-escalated, and eleven answers that came
back with a malformed rule code and had to be asked again. Both prompts stay
registered as separate engines, so none of this is a claim — it is a row.

Three limits, stated rather than left to be discovered.

**v2's score is optimistic and there is no way to say by how much.** Its wording
was written after reading v1's mistakes on this dataset, so 0.924 measures a
diagnosis confirmed on the data that produced it, not a held-out result. What it
does establish is that the diagnosis was right: the change was aimed at one
class and that class is what moved. Treating it as an estimate of live traffic
would be wrong.

**The violating half of the dataset was written for it.** The benign half is real
forum content; a seeded demo application contains no abuse to sample. Since
provenance and label are then almost perfectly correlated, the report detects
that and refuses to present the per-source gap as a finding.

**The three engines were measured in two runs, not one.** The free tier allows
500 requests a day and three engines over 192 samples needs 588. The committed
report holds `keyword-v1` and `gemini-3.5-flash-lite/v2`; v1's row is the
previous run, in git history, on the same dataset and the same code. Rerun with
`--campusguard.evaluation.engines=...` to scope a run to what a quota allows.

## Tests

```bash
mvn verify
```

Integration tests start their own PostgreSQL through Testcontainers, so the
Compose stack does not need to be running. They use a real database rather than
an in-memory substitute because this schema's correctness lives in partial
indexes, check constraints and unique indexes that an in-memory engine does not
enforce — including the one that makes concurrent reports collapse into a single
case, which is covered by a test that fires two of them at once.

## Some decisions worth the words

**No RAG.** A few dozen rules fit in a prompt with room to spare, so retrieval
would add a vector store, an embedding pipeline and a relevance failure mode in
exchange for nothing. `RuleProvider` is an interface so that changes when the
rule set does.

**Schema owned by Flyway, `ddl-auto: validate`.** An entity that disagrees with a
migration fails at startup instead of silently mutating the database. It has
caught real drift more than once.

**`open-in-view: false`.** Keeping a session open through view rendering hides
lazy-loading problems; with it off, an unfetched association fails loudly rather
than turning a feed into one query per row.

## Project ownership

Everything here — the Spring Boot backend, the moderation workflow, the database
design, the evaluation harness and the tests — is designed and implemented by
Mingjie Mao.

The benign half of the evaluation dataset is seeded forum content taken verbatim
from an earlier ANU team project, [De-discussion](https://github.com/Mingjie-Mao/De-discussion).
It is used as data and nothing else; none of that project's code is in this
repository.
