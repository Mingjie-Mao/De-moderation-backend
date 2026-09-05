# Client integration guide

The backend is the source of truth whenever Android's backend switch is enabled.
UI data in `AppData` is a cache only: writes wait for a successful server response
and forum/thread screens refresh from the API.

## Authentication

Register or log in to obtain an access token and a rotating refresh token. Send
the access token as `Authorization: Bearer …`. On a 401, rotate once through
`POST /api/auth/refresh`; if rotation fails, return to login. Never store an
administrator password in a mobile build.

Password changes and `POST /api/auth/logout-all` increment a per-user token
version, so every older access token is rejected on its next request. Refresh
tokens are stored only as SHA-256 digests and each token can be consumed once.

## Forum and media flow

1. If an image is present, upload it first with multipart `POST /api/media`.
2. Pass the returned `mediaId` in a post or comment create/update request.
3. Read the canonical item returned by the write; do not invent a client id.
4. Refresh `GET /api/posts?forum=…` or
   `GET /api/posts/{id}/comments` after reconnecting.

Uploads are limited to 8 MiB and 20 megapixels. The server decodes and rewrites
JPEG/PNG content, stripping metadata before storage. Only the uploader can attach
an object; media referenced by visible content is publicly readable and carries
immutable cache headers.

## Moderation, notifications and appeals

Reports accept an optional `details` field. A report produces or joins one
durable moderation case. The reporter and affected author receive a
`MODERATION_DECISION` notification when a human decides it.

The affected author can appeal a resolved `HIDE`, `DELETE` or `BAN` action with
`POST /api/appeals`. Only one pending appeal per author/case is allowed. Admins
process appeals in the web console; an overturn revises the original case to
`NONE`, reverses its effects and appends audit entries. Clients list and mark
notifications read through `/api/notifications`.

## Browser admin application

Build `admin-web` with `NEXT_PUBLIC_API_BASE_URL` set to the public API origin.
That exact origin must be in `CORS_ALLOWED_ORIGINS`. The admin application uses a
session-scoped bearer token, supports case claim/release, blocks conflicting
reviewers, records decision notes, displays media evidence/audit trails and
processes appeals. It contains no bundled administrator credential.
