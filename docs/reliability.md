# Reliability decisions

What happens when something fails, and what stops one account from overwhelming
the thing that is supposed to police it.

## The queue is durable, not merely asynchronous

Reports become moderation cases; a worker claims a batch and analyses it. The
claim uses `SELECT ... FOR UPDATE SKIP LOCKED`, so a second instance takes
different rows rather than queueing behind rows the first already holds, and the
claim commits before analysis starts so no row lock is held across a model call.

That leaves one hole: a worker that dies between claiming a case and finishing it
leaves a row in `ANALYSING` that nothing would ever look at again — not the claim
query, which only reads `QUEUED`, and not a person, because the reporter was
already told it would be reviewed.

A sweep returns those cases to the queue. It is tested in both directions,
because it can be wrong twice:

- **Too slow**, and reports die quietly.
- **Too eager**, and it hands a case to a second worker while the first is still
  mid-call — the same content judged twice and billed twice.

The tests were verified by breaking the sweep's query and watching the right one
fail, rather than by trusting four green ticks.

## Model failure degrades; it does not stop

A missing API key, a timeout, a rate limit, a provider outage or a response that
fails validation all fall back to the deterministic rule engine. The verdict
records which engine actually answered, so a rule-engine result is never
attributed to the model — that would corrupt the comparison the evaluation exists
to make.

Running with no model configured at all is a supported configuration, not an
error. With `AI_CHAT_MODEL` unset there is no model engine registered and
everything else works unchanged.

Around the call itself:

- **Timeout, then circuit.** A timeout alone spends the full budget on every
  request while a provider is down. The circuit turns a thirty-second failure
  into an instant one, so the queue degrades at full speed instead of crawling.
- **Backoff only where it helps.** Rate limiting is retried with exponential
  backoff and jitter, outside the call budget, because waiting is the one thing
  that actually fixes it. A refused credential is not retried — it will still be
  refused in two seconds.
- **Validation, then one corrective retry.** A response that parses is not a
  response that is correct. A confidence outside `[0,1]`, a decision that is not
  one of the three, or a rule code that does not exist are rejected and the
  specific complaint is fed back. It is load-bearing: across one 192-sample run
  it caught forty answers whose rule code carried its severity along with it
  (`"ABUSE (HIGH)"`), and the retry fixed every one.
- **Every call recorded.** `ai_invocations` holds model, prompt version, tokens,
  latency, status and the raw response, for successes and failures alike. The
  failure rate is the number that says whether the fallback is load-bearing or
  decorative.

## Reply nesting is capped in the schema

Threads are assembled by recursing once per nesting level, and nothing limited
how deep a reply could go. A chain of eight thousand answered the public comments
endpoint with a `StackOverflowError` — measured on a running server, and
reachable by a single account replying to itself.

The ceiling is a `CHECK` constraint on a stored `depth` column, not a guard in
the service that happens to write comments today. A constraint binds every
writer: an import, a backfill, a second service. A check in one method binds only
callers who go through that method. Verified by inserting a depth of 99 straight
past the application and watching the database refuse it.

The service checks first anyway, so the answer is a sentence somebody can act on
rather than a constraint violation arriving as a 500. Ten levels is past the
point where a client renders the nesting at all.

## Threads and feeds page by cursor

An offset is wrong for a feed. New rows arrive at the top, so every insertion
shifts the page and a reader paging through sees some posts twice and never sees
others. Keyset paging seeks past a known position instead, and lets the database
stop reading once it has enough rows rather than counting past the ones it skips.

The cursor carries `(created_at, id)` together, because the instant alone is not
a unique position: two rows created in the same microsecond leave the boundary
ambiguous. It is base64-encoded so clients treat it as a token to hand back
rather than a timestamp to do arithmetic on.

Threads page the same way, by top-level comment. Only roots are paged — a reply
cannot be rendered without the comment it answers, so half a conversation is not
something a client can reassemble. A page is a whole conversation, and depth is
bounded separately by the constraint above.

## Authoring is rate limited, not just reporting

Reporting was capped from the first version; authoring was not. That had the
asymmetry backwards. A report costs a moderator one glance at something a person
already chose to flag. A post costs an engine call, a queue slot and a reviewer's
attention the moment anyone reports it. Registration is open, so being signed in
was never a brake on a script.

Generous on purpose — a ceiling on flooding, not a throttle on enthusiasm. A
person arguing at midnight should never meet it; a script should meet it almost
at once.

## Concurrent reports collapse into one case

Several reports on one target become one case and therefore one engine call. The
guarantee is a partial unique index rather than a check in application code, so
two reports arriving at the same instant cannot both create a case. It is covered
by a test that fires two of them at once against a real PostgreSQL.

## Schema drift fails at startup

Flyway owns the schema and Hibernate runs with `ddl-auto: validate`. An entity
that disagrees with a migration stops the application from starting instead of
silently mutating the database. It has caught real drift more than once —
including a `CHAR(64)` column that should have been `VARCHAR`.

`open-in-view` is off, so an unfetched association fails loudly rather than
turning a feed into one query per row.
