[![English](https://img.shields.io/badge/English-1f6feb?style=for-the-badge)](README.md)
[![中文](https://img.shields.io/badge/%E4%B8%AD%E6%96%87-grey?style=for-the-badge)](README.zh-CN.md)

# De-Moderation

**De-Moderation is a content moderation backend built for the
[De-discussion](https://github.com/Mingjie-Mao/De-discussion) campus forum,
combining a rule engine with LLM-assisted review while an administrator makes the
final call.**

When a user reports content the system opens a moderation case, a pluggable rule
or LLM engine produces a recommendation, and a human completes the decision.

Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Spring AI (Gemini) · Testcontainers

[Architecture](docs/architecture.md) · [Evaluation](docs/evaluation-notes.md) ·
[Reliability](docs/reliability.md) · [Security](docs/security-decisions.md) ·
[Production](docs/production-runbook.md) · [Client guide](docs/api-client-guide.md) ·
[Demo script](docs/demo-script.md) · [Full backend report](docs/backend-project-report.md)

## Results

Every moderation engine is tested by the same evaluation pipeline, on the same
192-sample bilingual labelled dataset.

| Engine | Macro-F1 | ALLOW Recall | REMOVE Recall | ESCALATE Recall | p50 Latency | Tokens/sample |
|---|---|---|---|---|---|---|
| `keyword-v1` — term list | 0.286 | 1.000 | 0.106 | 0.000 | 0.05 ms | — |
| `gemini-3.5-flash-lite/v1` | 0.617 | 0.978 | 0.939 | 0.056 | 906 ms | 341 |
| `gemini-3.5-flash-lite/v2` | **0.924** | 0.989 | 0.970 | **0.778** | 868 ms | 651 |

v1 and v2 use the same model and the same Java code; only the prompt changed.
Rewriting it took Macro-F1 from 0.617 to 0.924 and ESCALATE Recall from 0.056 to
0.778, at the cost of average prompt token usage rising from 341 to 651.

**Note:** v2 was written after inspecting v1's errors on this same dataset, so
0.924 is not an unbiased held-out estimate. Per-sample disagreements, the
English/Chinese split and the remaining limits are in the
[evaluation notes](docs/evaluation-notes.md).

## Architecture

```mermaid
flowchart LR
    R["Report"] --> C["QUEUED"]
    C --> W["Worker claims<br/>FOR UPDATE SKIP LOCKED"]
    W --> A["ANALYSING"]
    A --> E["Engine<br/>rule / model"]
    E --> Q{"call<br/>succeeded?"}
    Q -->|"yes"| AR["AWAITING_REVIEW"]
    Q -->|"no"| F["rule fallback<br/>recorded as degraded"]
    F --> AR
    AR --> ADM["Administrator<br/>decides"]
    ADM --> RES["RESOLVED<br/>NONE / HIDE / DELETE / BAN"]
    A -.->|"worker times out<br/>or disappears"| C
```

**Design principles**
- **Human-in-the-loop:** an engine only produces a recommendation, a confidence
  and a rationale; the final action is always an administrator's.
- **Correctable:** resolved cases support re-decision and appeal. Effects of a
  prior decision can be undone, and every change is appended to the audit trail.
- **Fail-safe moderation:** an external model failure never blocks the review
  workflow.

Repeated reports on one target collapse into a single moderation case, and a
database unique constraint guarantees only one engine call. Cases left behind by
a worker that exited abnormally are automatically requeued; every state
transition, verdict and administrator action is written to an append-only audit
log.

### Case investigation (in progress)

The pipeline above decides one piece of content at a time. What it cannot tell a
reviewer is whether this is the author's first offence or their fourth, or how
the same rule has been enforced before — cases are keyed by the content they
concern, so there was no path from a person to their history.

A reviewer can ask an assistant to go and find out. It is a second, optional
path hanging off `AWAITING_REVIEW`; it moves no case and writes nothing.

```mermaid
flowchart LR
    AR["AWAITING_REVIEW"] -->|"reviewer asks"| M["Model turn<br/>same circuit breaker<br/>as the engine"]
    M --> D{"tool call<br/>or brief?"}
    D -->|"tool call"| T["ToolRegistry<br/>whitelist · read-only tx"]
    T -->|"result + case ids disclosed"| M
    D -->|"brief"| P{"is every citation<br/>one it actually read?"}
    P -->|"no · 1 retry"| M
    P -->|"yes"| BR["Brief<br/>recommendation · confidence<br/>counter-evidence"]
    M -.->|"timeout · circuit open"| PA["Partial"]
    D -.->|"5 lookups spent"| IN["Inconclusive"]
    P -.->|"still fabricating"| IN
    BR --> ADM["Administrator decides<br/>still the only writer"]
    PA --> ADM
    IN --> ADM
```

The four tools are `caseDetail`, `authorHistory`, `similarResolvedCases` and
`ruleText`. Each takes the case under investigation from the caller rather than
from the model's arguments, so there is no way to point one at a different case.

**What is deliberately absent.** No memory: the durable state is PostgreSQL,
with transactions and an audit trail, and a model's private second copy would be
a second answer to questions that must have exactly one. No retrieval: the rule
set is a few dozen entries and fits in a prompt, so a vector store would buy a
new failure mode and nothing else. No autonomy on the main path: the pipeline
above is unchanged, and the evaluation numbers keep meaning what they meant.

**Status.** The data layer, the tool registry, the loop and the shared
resilience policy are implemented and tested against a scripted model. It is not
yet wired to a real one, has no HTTP endpoint, and is off by default.

## Features

- **Forum and user API** — posts, threaded comments, profiles, cursor pagination,
  normalized images and notifications
- **Secure sessions** — JWT, rotating refresh tokens, instant invalidation,
  password change/reset and persistent authentication throttling
- **Durable moderation workflow** — report aggregation, a persistent case queue,
  concurrent workers and recovery of abandoned cases
- **Pluggable moderation engines** — rule and LLM engines share one interface and
  are evaluated independently by model and prompt version
- **LLM reliability** — output validation, timeout, circuit breaking,
  rate-limit backoff and deterministic fallback
- **Human-in-the-loop review** — claim, evidence, decisions, correction, appeal,
  notifications and an append-only audit trail
- **Deployment and observability** — Docker, TLS, Prometheus/Grafana, backup
  scripts and CI, plus Kubernetes and k6 configuration templates

## Quick start

Requires JDK 21 and Docker, or a compatible container runtime.

Copy the environment template:

```bash
cp .env.example .env
```

Fill in `DB_PASSWORD` in `.env`, and generate a `JWT_SECRET`:

```bash
openssl rand -hex 32
```

Start the database, backend and local reviewer console:

```bash
docker compose up -d
set -a && . ./.env && set +a
mvn spring-boot:run
(cd admin-web && npm ci && npm run dev)
```

Once running, the reviewer console is at [localhost:3000](http://localhost:3000)
and Swagger UI is at [localhost:8080](http://localhost:8080/swagger-ui.html).

### API permissions

| Endpoint | Access |
|---|---|
| `POST /api/auth/register` · `/login` · `/refresh` · password reset | public |
| `GET /api/moderation/status` | public; active engine capability only |
| `GET /api/posts` · `/{id}` · `/{id}/comments` | public |
| Posts/comments create, edit and delete; media, reports, appeals, notifications | signed-in users (ownership enforced) |
| `GET\|POST /api/admin/moderation-cases/**` | administrators only |

The administrator role is not granted through any public API. Set
`ADMIN_USERNAME` and `ADMIN_PASSWORD` in `.env` and that account is created with
the `ADMIN` role at startup.

The initializer only creates a missing username; it never promotes an existing
account or overwrites an existing password.

## AI-assisted moderation

LLM moderation is optional. With `AI_CHAT_MODEL` unset the model engine is never
registered and everything else runs normally.

Add to `.env`:

```bash
printf 'AI_CHAT_MODEL=google-genai\nGEMINI_MODELS=gemini-3.5-flash-lite\nMODERATION_ENGINE=gemini-3.5-flash-lite/v2\nGEMINI_API_KEY=...\n' >> .env
```

The model and the prompt version together form an engine's identity — for
example `gemini-3.5-flash-lite/v2` — so different prompts are registered,
evaluated and compared independently.

The LLM call chain includes:

- **One engine interface** — the rule engine and the LLM share it, decoupling the
  model implementation from the core moderation flow
- **Structured-output validation** — the decision, confidence and rule codes are
  checked, and invalid output triggers one corrective retry
- **Timeout and circuit breaking** — a persistently failing model fails fast
  rather than blocking the moderation queue
- **Rate-limit backoff** — exponential backoff for recoverable throttling
- **Rule-engine fallback** — a model that fails for good falls back to the
  deterministic engine
- **Invocation records** — `ai_invocations` holds model, prompt version, token
  usage, latency, status and the raw response

The De-discussion Android client reads and writes forum content through this API;
administrator review and credential management are handled by the separate
browser console.

More implementation detail in [reliability.md](docs/reliability.md).

## Testing

Run the full suite:

```bash
mvn verify
```

The suite runs its integration tests against real PostgreSQL through
Testcontainers, covering concurrent aggregation, session rotation, media,
assignment, appeals, queue recovery and model degradation. The current test count
is printed by `mvn verify` and is kept out of prose so it cannot silently go stale.

## Dataset

The evaluation set is **192 bilingual labelled samples**, and contains no real
production traffic.

The benign samples come from the forum demo content of an earlier ANU team
project, [De-discussion](https://github.com/Mingjie-Mao/De-discussion); the
violating and borderline samples were written specifically for this evaluation.

## Documentation

| | |
|---|---|
| [architecture.md](docs/architecture.md) | components, data model, request flow |
| [evaluation-notes.md](docs/evaluation-notes.md) | what the numbers mean, and what they do not |
| [evaluation.md](docs/evaluation.md) | the generated report — metrics, confusion matrices, per-sample disagreements |
| [reliability.md](docs/reliability.md) | queue durability, degradation, bounds |
| [security-decisions.md](docs/security-decisions.md) | authentication, exposure, privilege |
| [demo-script.md](docs/demo-script.md) | a three-minute walkthrough |
| [api-client-guide.md](docs/api-client-guide.md) | Android/browser integration and token/media flows |
| [production-runbook.md](docs/production-runbook.md) | release, TLS, monitoring, backup and incident response |
| [backend-project-report.md](docs/backend-project-report.md) | complete backend design, implementation, evaluation and remaining work |
| [backend-project-report.zh-CN.md](docs/backend-project-report.zh-CN.md) | complete backend report in Chinese |
