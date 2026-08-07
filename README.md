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
printf 'AI_CHAT_MODEL=google-genai\nMODERATION_ENGINE=gemini-flash-latest/v1\nGEMINI_API_KEY=...\n' >> .env
```

What surrounds the call is the part worth reading:

- **A narrow port.** Everything vendor-specific lives in one class behind a
  three-method interface. That is what makes the resilience layer testable — a
  stub can hang, throw or lie without a network or a key.
- **Validation, not trust.** A response that parses is not a response that is
  correct. Confidence outside `[0,1]`, a decision that is not one of the three,
  or a rule code that does not exist are all rejected, and the complaint is fed
  back to the model as one corrective retry.
- **Timeout, then circuit.** A timeout alone still spends the full budget on
  every request while a provider is down; the circuit turns a ten-second failure
  into an instant one so the queue degrades at full speed.
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

The rule baseline exists so that a model's score means something. On 192 samples
it reaches macro-F1 0.286, and the reason is the number that matters: **recall on
REMOVE is 0.106**. Fifty-four of sixty-six violations pass straight through, and
it never once asks for a human. That is the floor a model has to beat, and by
enough to justify roughly 2.3 seconds per call against 0.05 milliseconds.

The report states its own limits rather than leaving them to be discovered. The
benign half of the dataset is real forum content; the violating half was written
for the evaluation, because a seeded demo application contains no abuse to
sample. Since provenance and label are almost perfectly correlated, the report
detects that and refuses to present the per-source gap as a finding.

## The Android client

The forum's feed, posting and reporting are served by this backend from a screen
in the [De-discussion](https://github.com/Mingjie-Mao/De-discussion) app, reached
under **Settings → CampusGuard backend**. Screenshots of it running against a
live server are in [`docs/screenshots`](docs/screenshots).

The client source is copied into [`examples/android-client`](examples/android-client) so this
repository stands on its own. It lives on a local branch of that app which is
deliberately not pushed: that repository belongs to a university team, and a
branch on it is theirs to accept rather than mine to publish. Its `main` is
untouched.

It is a separate screen rather than a new data source for the existing feed:
that app's `Post` model belongs to its course-provided data-structures module and
is threaded through the adapters and the moderation tools, none of which are
mine to destabilise. The transport is `HttpURLConnection` and `org.json`, because
three endpoints do not repay two new dependencies in a shared build file.

The emulator reaches the backend at `10.0.2.2:8080`, and cleartext is permitted
only to that address and to localhost.

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

The Spring Boot backend, moderation workflow, database design, evaluation harness
and tests are designed and implemented by Mingjie Mao.

The Android client is based on an earlier ANU team project; see that repository
for its own contributor list. The seeded forum content from that project is used
as the benign half of the evaluation dataset.
