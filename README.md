# CampusGuard Backend

Spring Boot backend and AI-assisted content moderation service for a university
discussion application.

> Work in progress. This README is a skeleton and will be filled in as the
> system takes shape.

## Stack

Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · Docker Compose

## Running locally

Requires JDK 21 and a Docker-compatible container runtime.

```bash
cp .env.example .env        # then edit the values
```

```bash
docker compose up -d        # starts PostgreSQL
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

## Project ownership

The Spring Boot backend, moderation workflow, database design, tests and
deployment configuration are designed and implemented by Mingjie Mao.

The Android client is based on an earlier ANU team project; see that
repository for its own contributor list.
