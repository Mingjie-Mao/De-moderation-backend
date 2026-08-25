# Production runbook

## Release gate

For the exact source and image tag being released, require all of the following:

```bash
mvn verify
(cd admin-web && npm ci && npm run lint && npm run build)
(cd ../De-discussion/android && ./gradlew test assembleDebug)
docker compose -f docker-compose.prod.yml config
k6 run load/k6-smoke.js
```

Use immutable container tags or digests. Never deploy from a mutable `latest`
tag, and never reuse a database password, JWT secret, administrator password or
SMTP credential between environments.

## Single-host HTTPS deployment

1. Point `DOMAIN` and `api.DOMAIN` DNS records at the host.
2. Copy `.env.example` to `.env`, set the required production values and keep the
   file readable only by the deployment user.
3. Generate `JWT_SECRET` with `openssl rand -hex 32` and use a long, unique
   administrator password.
4. Start with `docker compose -f docker-compose.prod.yml up -d --build`.
5. Caddy obtains and renews public TLS certificates automatically. Only ports 80
   and 443 are published. PostgreSQL, Prometheus, Grafana and the management port
   stay on internal Docker networks.
6. Verify `https://api.DOMAIN/actuator/health/readiness`, log into the admin web
   application and run one non-destructive test case before opening Android
   traffic.

The administrator bootstrap is create-only. Changing `ADMIN_PASSWORD` later does
not rotate an existing account; use the authenticated password-change endpoint.

## Backups and recovery

Schedule `scripts/backup.sh` from the host. It creates a PostgreSQL custom dump,
a media archive and SHA-256 checksums, then expires files older than
`RETENTION_DAYS` (14 by default). Copy backups to encrypted off-host storage and
test restoring them into a separate environment at least monthly.

`scripts/restore.sh` deliberately requires `--confirm-replace-database`. It stops
the API, replaces the database, optionally replaces media and restarts the API.
Validate Flyway, readiness, object counts and several media objects before
returning traffic.

For Kubernetes, prefer managed PostgreSQL automated snapshots. The supplied
manifest expects an RWX media volume; use an object-storage `MediaService` adapter
when the cluster cannot provide reliable shared storage.

## Monitoring and alerts

Prometheus scrapes the internal port at `/actuator/prometheus`. Important signals:

- `campusguard_moderation_cases{status=...}` — durable queue by state;
- `campusguard_moderation_sla_overdue` — cases past their review deadline;
- `campusguard_moderation_engine_degradations_total` — primary-engine failures;
- `campusguard_moderation_analysis_seconds` — analysis latency;
- Spring HTTP, JVM, database-pool and process metrics.

The provided rules alert on API downtime, a growing queue, SLA violations and a
5xx ratio above 2%. Configure Alertmanager receivers for the operator channel;
Prometheus alone evaluates rules but does not deliver pages.

## Incident actions

- **Model outage:** leave the service running. The circuit breaker and rule
  fallback keep cases moving. Confirm the degradation counter and inspect audit
  entries before changing engines.
- **Queue growth:** check worker health, database locks, provider rate limits and
  the oldest `QUEUED`/`ANALYSING` rows. Stalled analysis is requeued
  automatically.
- **Compromised account:** ban or suspend it, then invoke logout-all. Token version
  checks reject previously issued access tokens immediately and refresh tokens
  are removed.
- **Media volume full:** stop uploads at the edge, expand the volume, verify file
  and database consistency, then resume. Do not delete database-referenced files.
- **Database incident:** take the API read-only/offline, preserve the failed
  instance for analysis, restore into a new database, verify, then switch traffic.
