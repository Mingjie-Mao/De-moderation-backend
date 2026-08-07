# Screenshots

The Android client on the `campusguard-api-integration` branch of De-discussion,
talking to this backend. Captured from a running emulator against a live server,
not mocked up.

| | |
|---|---|
| `01-feed-from-backend.png` | The feed is `GET /api/posts`. Bilingual content, newest first, paged by cursor. |
| `02-report-from-app.png` | Long press on a post opens the reason list; choosing one is `POST /api/reports`. |
| `03-recommended-removal-still-visible.png` | **The important one.** `gemini-v1` had already returned REMOVE at confidence 0.98, with the rationale "The post directly insults another user, violating the rule against abusive behavior and personal insults." The post is still at the top of the feed. |
| `04-after-the-administrator-decided.png` | The same feed after an administrator resolved the case as HIDE. |

The gap between the third and fourth screenshot is the entire point of the
system: an engine recommends, a person decides, and nothing is removed in
between. The audit trail for that case reads:

```
SYSTEM:CASE_OPENED  USER:REPORT_FILED  SYSTEM:CASE_CLAIMED
ENGINE:VERDICT_RECORDED  ADMIN:CONTENT_HIDDEN  ADMIN:CASE_RESOLVED
```
