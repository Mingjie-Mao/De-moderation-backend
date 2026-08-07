# Demo script

Roughly three minutes. The order matters: the point of the whole system is the
moment where the engine has decided something and the content is still there.

## Before recording

```bash
docker compose up -d
set -a && . ./.env && set +a && mvn spring-boot:run
```

Create an administrator, since no endpoint grants the role:

```bash
docker exec -e PGPASSWORD="$DB_PASSWORD" campusguard-postgres \
  psql -U "$DB_USER" -d "$DB_NAME" -c "update users set role='ADMIN' where username='<your admin>';"
```

Have open: the Android emulator, Swagger UI, and a terminal on the application
log.

## 1 — The client is talking to the backend (30s)

Open the app, **Settings → CampusGuard backend**. Register and sign in.

Create a post through the app. Then show the same post over the API:

```bash
curl 'http://localhost:8080/api/posts?forum=anu-general&size=5'
```

Say: the feed is not demo data any more, and it pages by cursor rather than
offset so a post arriving mid-scroll does not shift the list.

## 2 — Report it (20s)

Long press the post in the app, choose a reason. Point out the response says
`AGGREGATED`, not `PENDING`: the report has already been folded into a case.

Report the same post again from the app. It is refused with the backend's own
sentence, "you have already reported this content" — that is a unique index
answering, not a check in application code.

## 3 — The queue moves on its own (20s)

Show the log picking the case up within a poll. Say what the query is:
`SELECT ... FOR UPDATE SKIP LOCKED`, so a second instance takes different rows
instead of queueing behind the first, and the claim commits before analysis
starts so row locks are not held across a model call.

## 4 — The part that matters (30s)

In Swagger, `GET /api/admin/moderation-cases`. Show the case: engine name,
recommendation, confidence, rationale, rule codes.

Then switch back to the app and refresh. **The post is still there.**

Say it plainly: the engine recommended removal and nothing happened, because an
automated system that takes content down on its own is one nobody can appeal to.
A person decides.

## 5 — The decision (20s)

In Swagger, `POST /decision` with `HIDE`. Show the audit trail in the response:

```
SYSTEM CASE_OPENED · USER REPORT_FILED · SYSTEM CASE_CLAIMED
· ENGINE VERDICT_RECORDED · ADMIN CONTENT_HIDDEN · ADMIN CASE_RESOLVED
```

Refresh the app. The post is gone.

## 6 — Pull the plug (30s)

Stop the application, remove `AI_CHAT_MODEL` from `.env`, start it again. Report
something else and let it run.

The case still reaches a verdict, and the engine on it reads `keyword-v1`. Say:
a missing key degrades moderation to rules; it does not stop it. There are
automated tests for the three ways this fails — credentials refused, a call that
never returns, and a response that parses but carries an impossible confidence.

## 7 — The numbers (30s)

Open [`evaluation.md`](evaluation.md). Do not read the accuracy figure out loud;
read the recall.

The rule engine catches 10.6% of violations and never once asks for a human. That
is the floor. A model has to beat it by enough to justify roughly 2.3 seconds a
call against 0.05 milliseconds — which is the comparison the harness exists to
make, and why the baseline was measured before there was anything to compare it
to.

Close on the dataset's limits: the benign half is real forum content, the
violating half was written for the evaluation, and the report says so itself
rather than waiting to be caught.
