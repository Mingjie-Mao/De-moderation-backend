# Security decisions

Each of these was a framework default before it was a decision. They are written
down because the default was wrong for this system in a way that was not visible
from reading any controller.

## Revocation takes effect on the next request

A signed token is a statement about the past: who this was and what they could do
at the moment it was issued. For most of an API that is close enough.

It is not close enough here. Banning is the strongest action the moderation
console offers and it is aimed at somebody actively causing harm, so a token that
stays valid for its full hour hands that person another hour of posting after a
moderator has decided they should stop.

Measured rather than assumed. Suspending an account in the database and then
posting with the token it already held returned `201 Created`.

The same staleness ran the other way and was worse: a demoted administrator kept
`ROLE_ADMIN` in their claims until expiry, so for an hour they could still
resolve cases and ban other people.

`AccountStateFilter` re-reads the account on every authenticated request.
Suspended or deleted is rejected; authorities are rebuilt from the stored role
rather than from the claim, so a promotion is picked up on a token the user
already holds. One primary-key lookup per request buys it. A revocation list or
refresh tokens would both be more machinery than a forum this size justifies.

The same check now includes `tokenVersion`. Password change, logout-all and
security administration increment it, invalidating every older access token on
its next request. A normal logout deletes the current refresh-token family;
refresh tokens rotate on every use and are stored only as SHA-256 digests. This
keeps short-lived access tokens convenient without making a stolen long-lived
refresh token reusable forever.

Password-reset tokens are likewise random, short-lived and stored as digests.
Login, refresh, registration and reset entry points use persistent IP/account
rate-limit buckets so restarting or horizontally scaling the API does not reset
the protection.

**Worth knowing:** the filter is deliberately not a `@Component`. Spring Boot
auto-registers any `Filter` bean straight into the servlet container's chain, so
annotating it installs it twice — once where the security configuration asks for
it, and once outside the security chain entirely at an ordering nobody chose.
This surfaced when removing the `addFilterAfter` line failed only two of six
tests instead of four.

## Actuator answers strangers with one word

`/actuator/health` stays public because an orchestrator has no credential and
still has to decide whether the instance is alive. The details do not.

On Spring's default of `show-details: always`, an anonymous `GET` returns:

```json
"diskSpace": { "path": "/srv/campusguard/.", "total": 994662584320, "free": 506044456960 },
"db":        { "database": "PostgreSQL" }
```

The absolute deployment path, the disk geometry and the database engine, to
anyone who asks. It is now `when-authorized` with `roles: ADMIN`, so an anonymous
caller gets the one word a load balancer reads.

Everything else under `/actuator` is administrator-only too. The framework's
fallback is "any authenticated user", which on a forum anyone can register for is
not a bar at all — an account a minute old could read JVM internals and HTTP
timings.

The test that pins this deliberately does **not** set these properties itself. A
test that supplies the configuration it then asserts passes forever while
production says something else.

## The error page is public

Spring forwards an unhandled exception to `/error`, and that forward is a fresh
request carrying no credential. With `/error` closed, every error on a *public*
endpoint came back as `401 Authentication required`.

That is worse than a wrong status code. It points whoever is debugging at an
authentication problem that does not exist while the real failure sits in the
log — which is exactly how a `StackOverflowError` in the comments endpoint
presented itself. The body is still built by the problem-detail handler, so
opening the path discloses nothing extra.

## API documentation is exposed by choice, not by default

Swagger UI fetches the specification from the browser with no `Authorization`
header, so protecting the document leaves the local development viewer blank.
That is useful context for development, but it is not a reason to expose the
surface in production now that reviewers have a separate application.

So it is a property. `campusguard.security.expose-api-docs` defaults to true for
development and API exploration. The production profile disables both OpenAPI
and Swagger UI because the actual reviewer console is `admin-web`; administrators
do not depend on public API documentation.

## Privilege is not granted over the API

No endpoint promotes anyone to administrator. The role is set directly in the
database. An API that hands out privilege on request hands it to whoever asks.

## The rest, briefly

- **HS256 is pinned on the decoder.** Left open, a decoder honours whatever
  algorithm the token's own header asks for — the caller picking the verification
  rules is the classic algorithm-confusion attack.
- **No default signing key.** The application refuses to start without
  `JWT_SECRET`, and checks it is at least 256 bits. A fallback secret is the kind
  of thing that reaches production unnoticed, and a token signed with a publicly
  known key is not authentication.
- **CSRF is disabled, and only safely.** This API is stateless and carries its
  credential in a header; CSRF exists to stop a browser attaching an ambient
  cookie to a cross-site request, and nothing here is ambient. Reintroducing
  cookie sessions would mean reinstating it.
- **CORS is explicit.** Only configured browser origins can call the API; bearer
  tokens are not cookies and credentialed cross-origin requests are disabled.
- **Reviewer credentials stay out of Android.** The member app has no online
  reviewer login or admin API calls. Privileged sessions exist only in the
  browser console's session storage and disappear when that tab session ends.
- **Uploaded media is decoded and rewritten.** JPEG/PNG content is bounded by
  bytes and decoded pixels, metadata is stripped, ownership is checked before an
  attachment is referenced, and public reads are allowed only for media attached
  to visible content.
- **Passwords are BCrypt.** Deliberately slow and salted per password by
  construction, so a leaked table cannot be attacked with precomputed hashes.
- **Authorization is by route prefix, not per method.** `/api/admin/**` is
  administrator-only and `anyRequest()` is authenticated, so a new administrative
  route is restricted by default rather than restricted only if somebody
  remembers the annotation.
