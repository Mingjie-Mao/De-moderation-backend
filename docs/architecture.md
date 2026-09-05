# Architecture

## The system

```mermaid
flowchart LR
    android["Android member app<br/>backend-first UI cache"]
    admin["Browser reviewer console"]

    subgraph backend["De-Moderation backend"]
        api["REST API<br/>JWT, RFC 7807"]
        media["Media service<br/>decode, normalize, hash"]
        workflow["Moderation workflow<br/>state machine"]
        worker["Async worker<br/>SKIP LOCKED"]
        registry["ModerationEngine<br/>registry"]
        keyword["keyword-v1<br/>rules"]
        gemini["model/prompt-version<br/>Spring AI"]
        eval["Evaluation harness"]
    end

    db[("PostgreSQL 16")]
    files[("Media volume / object store")]
    google["Gemini API"]

    android -->|"profile, feed, media, report, appeal"| api
    admin -->|"claim, review, decide"| api
    api --> workflow
    api --> media
    media --> files
    workflow --> db
    worker -->|"claim queued cases"| db
    worker --> registry
    registry --> keyword
    registry -.->|"only when configured"| gemini
    gemini -->|"timeout, circuit, backoff"| google
    gemini -.->|"on failure"| keyword
    eval -->|"scores every engine<br/>on one dataset"| registry
    eval --> db
```

The dotted edges are the ones worth reading. A model engine exists only when one
is configured, and when it fails the queue falls back to `keyword-v1` rather than
stopping. Nothing else in the diagram changes shape when the model is absent.

There is one model box per model-and-prompt pair, named `model/version` — two
prompt revisions of one model are two engines, so the harness scores them against
each other instead of the newer one quietly replacing the older one's numbers.

## What happens to a report

```mermaid
sequenceDiagram
    participant M as Member
    participant API as REST API
    participant DB as PostgreSQL
    participant W as Worker
    participant E as Engine
    participant A as Administrator

    M->>API: POST /api/reports
    API->>DB: open or join the case for this target
    Note over DB: partial unique index on open cases:<br/>concurrent reports collapse into one
    API-->>M: 201, status AGGREGATED

    W->>DB: SELECT ... FOR UPDATE SKIP LOCKED
    W->>DB: mark ANALYSING, commit
    Note over W,DB: claim commits before analysis, so row<br/>locks are not held across a model call
    W->>E: evaluate
    alt engine answers
        E-->>W: decision, confidence, rationale, rule codes
    else engine fails
        E-->>W: timeout, invalid response, or throttling
        W->>E: fall back to the rule engine
    end
    W->>DB: record verdict, move to AWAITING_REVIEW

    Note over A: reviewer claims the case;<br/>the content is still visible here
    A->>API: POST /claim, then POST /decision {HIDE}
    API->>DB: hide content, resolve case, resolve its reports
    API->>DB: audit entry for every step
```

## Case lifecycle

```mermaid
stateDiagram-v2
    [*] --> QUEUED: first report opens a case
    QUEUED --> ANALYSING: worker claims it
    ANALYSING --> QUEUED: worker died, swept back
    ANALYSING --> AWAITING_REVIEW: verdict recorded
    AWAITING_REVIEW --> AWAITING_REVIEW: claim or release reviewer
    AWAITING_REVIEW --> RESOLVED: administrator decides
    RESOLVED --> [*]

    note right of AWAITING_REVIEW
        Every path out of analysis arrives here,
        including the failures. A case that could
        not be judged is one a person should see.
    end note

    note right of RESOLVED
        NONE, HIDE, DELETE or BAN.
        The only place content is removed.
    end note
```

Assignment is metadata rather than another case state. A claim records the
reviewer and prevents a conflicting decision while the case remains
`AWAITING_REVIEW`; it can be released without inventing another lifecycle state.
The review deadline is also stored on the case so SLA alerts do not depend on a
dashboard calculating time from memory.

## Schema

```mermaid
erDiagram
    users ||--o{ posts : writes
    users ||--o{ comments : writes
    users ||--o{ reports : files
    posts ||--o{ comments : has
    comments ||--o{ comments : replies_to
    moderation_cases ||--o{ reports : aggregates
    moderation_cases ||--o{ ai_invocations : bills
    moderation_cases ||--o{ appeals : challenged_by
    users ||--o{ notifications : receives
    users ||--o{ refresh_tokens : owns
    users ||--o{ media_objects : uploads
    moderation_rules }o--o{ moderation_cases : cited_by

    posts { uuid id PK "soft deleted" }
    comments { uuid id PK "parent pointer" }
    reports { uuid target_id "no FK: post or comment" }
    moderation_cases { uuid target_id "unique while open" }
    appeals { uuid id PK "one pending per author/case" }
    media_objects { uuid id PK "normalized object metadata" }
    ai_invocations { jsonb raw_response "success and failure alike" }
    audit_log { jsonb payload "append only, no FK to actor" }
```

Three deliberate absences:

`reports.target_id` and `moderation_cases.target_id` carry no foreign key,
because each points at a post *or* a comment and one key cannot express that. The
alternative — two nullable columns behind a check constraint — keeps referential
integrity but makes every read path branch on which column is populated.

`audit_log.actor_id` carries no foreign key so that an entry outlives the account
it describes. A log that loses its subject when a user is deleted is not evidence.

`moderation_cases` has no global total-count column and the feed has no count query. A
number that costs a full scan and is stale on arrival is not worth the scan.

## Where the seams are

| Seam | Why it exists |
|---|---|
| `ModerationEngine` | Rules and models satisfy the same interface, so the fallback is possible at all and the evaluation harness can score both with one body of code. |
| `ChatCompletionPort` | All vendor knowledge in one class. The timeout, circuit breaker and backoff sit around it and are therefore testable against a stub that hangs, throws or lies. |
| `RuleProvider` | One query today. If the rule set ever outgrows a prompt, retrieval becomes a new implementation rather than surgery on every caller. |
| Services take an actor id | Authentication stays in the web layer, so the JWT work touched controllers only and services stay unit-testable. |
