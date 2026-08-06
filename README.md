# CampusGuard Backend

Spring Boot backend and AI-assisted content moderation service for a university
discussion application.

> Work in progress. This README grows with the system.

## Stack

Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · Spring Security · Docker Compose

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

Check it is alive:

```bash
curl http://localhost:8080/actuator/health
```

## Trying the API

Swagger UI at <http://localhost:8080/swagger-ui.html> is the primary console.
Register or log in under **Authentication**, then paste the returned
`accessToken` into **Authorize** to exercise everything else.

Reading the forum is open. Every write, and reading a report, needs a bearer
token.

| | |
|---|---|
| `POST /api/auth/register` · `POST /api/auth/login` | public |
| `GET /api/posts` · `GET /api/posts/{id}` · `GET /api/posts/{id}/comments` | public |
| `POST /api/posts` · `POST /api/posts/{id}/comments` · `POST /api/reports` | any signed-in member |
| `DELETE /api/posts/{id}` | the post's author, or an administrator |
| `GET /api/reports/{id}` | the report's author, or an administrator |

There is no endpoint that grants the administrator role; it is set directly in
the database. An API that hands out privilege on request hands it to whoever
asks.

## Tests

```bash
mvn verify
```

Integration tests start their own PostgreSQL through Testcontainers, so the
Compose stack does not need to be running. They use the real database rather
than an in-memory substitute because this schema's correctness lives in partial
indexes, check constraints and unique indexes that an in-memory engine does not
enforce.

## Project ownership

The Spring Boot backend, moderation workflow, database design, tests and
deployment configuration are designed and implemented by Mingjie Mao.

The Android client is based on an earlier ANU team project; see that
repository for its own contributor list.
