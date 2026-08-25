# Demo script

Roughly three minutes. The order matters: the point of the whole system is the
moment where the engine has decided something and the content is still there.

## Before recording

```bash
docker compose up -d
```

```bash
set -a && . ./.env && set +a && mvn spring-boot:run
```

Create an administrator, since no endpoint grants the role:

```bash
docker exec -e PGPASSWORD="$DB_PASSWORD" campusguard-postgres \
  psql -U "$DB_USER" -d "$DB_NAME" -c "update users set role='ADMIN' where username='<your admin>';"
```

Have open: Swagger UI, and a terminal on the application log.
Also start `admin-web` with `npm run dev` and open `http://localhost:3000` for
the human-review steps.

## 1 — It is an ordinary HTTP API (30s)

Register a member and keep the token:

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"kai","password":"DemoPassw0rd1"}' | jq -r .accessToken)
```

Post something that breaks a rule, then read the feed back:

```bash
curl -s -X POST localhost:8080/api/posts -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"forumKey":"anu-general","title":"About the group project",
       "body":"You contribute nothing to this group and everyone in the tutorial knows it."}'
```

```bash
curl -s 'localhost:8080/api/posts?forum=anu-general&size=5'
```

Say: the feed pages by cursor rather than offset, so a post arriving mid-scroll
does not shift the list and a reader never sees the same post twice.

## 2 — Report it (20s)

```bash
curl -s -X POST localhost:8080/api/reports -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"targetType":"POST","targetId":"<post id>","reason":"ABUSE"}'
```

Point out the response says `AGGREGATED`, not `PENDING`: the report has already
been folded into a case.

Send the identical request again. It is refused with the backend's own sentence,
"you have already reported this content" — that is a unique index answering, not
a check in application code.

## 3 — The queue moves on its own (20s)

Show the log picking the case up within a poll. Say what the query is:
`SELECT ... FOR UPDATE SKIP LOCKED`, so a second instance takes different rows
instead of queueing behind the first, and the claim commits before analysis
starts so row locks are not held across a model call.

## 4 — The part that matters (30s)

In the browser reviewer console, open the queued case. Show its engine name,
recommendation, confidence, rationale, rule codes and audit history.

Then read the public feed again:

```bash
curl -s 'localhost:8080/api/posts?forum=anu-general&size=5'
```

**The post is still the first item.**

Say it plainly: the engine recommended removal and nothing happened, because an
automated system that takes content down on its own is one nobody can appeal to.
A person decides.

## 5 — The decision (20s)

Claim the case in the browser console, record a note, then choose `HIDE`. Show
the audit trail in the detail panel:

```
SYSTEM CASE_OPENED · USER REPORT_FILED · SYSTEM CASE_CLAIMED
· ENGINE VERDICT_RECORDED · ADMIN CONTENT_HIDDEN · ADMIN CASE_RESOLVED
```

Read the feed once more. The post is gone.

## 6 — Pull the plug (30s)

Stop the application, remove `AI_CHAT_MODEL` from `.env`, start it again. Report
something else and let it run.

The case still reaches a verdict, and the engine on it reads `keyword-v1`. Say:
a missing key degrades moderation to rules; it does not stop it. There are
automated tests for the three ways this fails — credentials refused, a call that
never returns, and a response that parses but carries an impossible confidence.

Worth adding if there is time: a worker killed mid-analysis is also covered.
The case goes back to the queue and gets judged by the next one, and the sweep
that does it is tested in both directions — it must return a case whose worker
died, and must not touch one somebody is still working on.

## 7 — The numbers (30s)

Open [`evaluation.md`](evaluation.md). Do not read the accuracy figure out loud;
read the recall.

The rule engine catches 10.6% of violations and never once asks for a human. That
is the floor, and it is why the baseline was measured before there was anything
to compare it to.

Then the two model rows, which are the more interesting result. Same code, same
model, two prompt versions: macro-F1 0.636 and 0.924. The difference is entirely
one class — the first prompt answered ALLOW to thirty-three of the thirty-six
samples that should have reached a person, because it was answering "does this
break a rule" when the queue is asking "can this be closed without a person".

Close on the limits, and do not wait to be asked: the violating half of the
dataset was written for the evaluation, and the second prompt was written after
reading the first one's mistakes on that same data, so its score there is
optimistic. The report says both itself.
