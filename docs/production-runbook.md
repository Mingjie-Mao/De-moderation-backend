# Production runbook

## Release gate

For the exact source and image tag being released, require all of the following:

```bash
mvn verify
(cd admin-web && npm ci && npm run lint && npm run build)
(cd ../De-discussion/android && ./gradlew test assembleDebug)
docker compose -f docker-compose.prod.yml config
scripts/render-alertmanager.sh
k6 run load/k6-mixed.js
scripts/restore-drill.sh
```

`mvn verify` starts PostgreSQL **and MinIO** through Testcontainers, so the
Docker daemon is a prerequisite of the test suite rather than only of the image
build.

`k6-mixed.js` replaces `k6-smoke.js` as the gate. The smoke script reads the
feed and nothing else, which is the cheapest path in the system; the mixed script
also signs in, writes, reports, uploads and lists cases, with a separate latency
budget per workload. `k6-smoke.js` is kept for a quick check against a running
host.

Run it against staging with all five per-account limits raised
(`campusguard.auth-rate-limit.registrations-per-ip`,
`campusguard.auth-rate-limit.logins-per-ip`,
`campusguard.auth-rate-limit.logins-per-account`,
`campusguard.content.posts-per-user`, `campusguard.reports.per-user-limit`).
`logins-per-account` is the one that is easy to leave off this list and the only
one that fails a budget by itself: it defaults to 12 per fifteen minutes, a
virtual user here is one account signing in for the whole run, and a run without
it comes back with the sign-in failure rate near 65% and every latency budget
still green, because a 429 is fast.
Against production values most of its virtual users are refused registration,
and the write, report and upload budgets end up measuring the limiter. Set
`HEALTH_URL` where Actuator is on the separate management port, as it is under
the production profile, and `ADMIN_USERNAME`/`ADMIN_PASSWORD` to include the case
list. The end of the run lists every budget with the figure it was held to and
whether it held, then `campusguard_throttled`: anything but zero means the limits
were not raised far enough. The full data is written to
`load/k6-mixed-summary.json`, which is gitignored.

`scripts/restore-drill.sh` restores the newest backup into a throwaway container
and checks the result. It touches nothing that is running. A release that has
never proved its backups restore is a release with no recovery plan, only a
recovery intention.

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

## Media storage

`MEDIA_BACKEND` picks between a directory and an S3-compatible bucket, and the
choice is load-bearing rather than a preference.

The filesystem backend is correct where a real volume is mounted and survives a
rebuild — which is true of the `media-data` volume in `docker-compose.prod.yml`
and false on a platform that rebuilds the container filesystem on release. On
such a platform it loses every uploaded image on every deploy, silently, while
the database keeps every row describing them. Nothing fails and nothing is
logged; old posts simply lose their pictures.

Set `MEDIA_BACKEND=S3` there. One implementation covers AWS S3, Cloudflare R2 and
GCS in interoperability mode; `MEDIA_S3_ENDPOINT` is what selects between them.
Use credentials scoped to the media prefix alone.

### Switching a running deployment to S3

The switch has been rehearsed end to end against MinIO with the deployment's own
variable names: an image uploaded through the API landed under the configured
prefix and survived a restart of the process byte-for-byte. What follows is the
same thing against a real bucket.

1. Create a bucket and an access key scoped to it. On R2 the endpoint is
   `https://<account-id>.r2.cloudflarestorage.com` and the region is `auto`; on
   AWS leave `MEDIA_S3_ENDPOINT` empty and set the real region.
2. Set `MEDIA_BACKEND=S3` and the six `MEDIA_S3_*` variables. Keep
   `MEDIA_S3_PATH_STYLE=true` for anything that is not AWS.
3. Deploy, and read the log for `Media storage is s3:<bucket>/<prefix>`. If it
   still says `filesystem:`, the variables did not reach the process and the
   switch has not happened — stop here rather than letting users upload into a
   disk that is about to be discarded.
4. Upload one image through the API, fetch it back, then redeploy and fetch it
   again. The second fetch is the whole point: it is the request that used to
   return 404 with the database row still present.
5. **Images uploaded before the switch are not migrated and are already gone** if
   the old directory did not survive a release. Their rows remain, which is what
   the sweep's "row(s) with no object" count reports. Copy them across from a
   media backup first if any of them still exist.

**Check this on every deploy.** The first hundred lines of the application log
carry `Media storage is ...`, naming the live backend. That line is the cheapest
way to catch a misconfiguration before users do. `Moderation engine is ...` sits
beside it and answers the same question for the thing that decides the cases — an
`MODERATION_ENGINE` that never reached the process leaves the queue running on
term matching, with every case still getting a verdict and nothing saying the
model was never asked. `GET /api/moderation/status` answers it after the fact,
and is public.

`MEDIA_SWEEP_ENABLED` runs an hourly sweep that deletes media nothing refers to
and reports rows whose bytes have gone. It never deletes media attached to hidden
or removed content, because a moderator's decision can be reversed and restoring
a post with a hole in it would make the reversal look like the fault. A rising
"row(s) with no object" count in its log is the signature of storage that is not
surviving deployment.

## Backups and recovery

Schedule `scripts/backup.sh` from the host. It creates a PostgreSQL custom dump,
SHA-256 checksums, and — only under `MEDIA_BACKEND=FILESYSTEM` — an archive of
the media directory, then expires files older than `RETENTION_DAYS` (14 by
default).

**Under `MEDIA_BACKEND=S3` the media is not in the backup.** The script has no
credentials for the bucket, and `/data/media` in the container is then an empty
mounted volume, so tarring it would write a hundred-byte file named like a media
archive and prove nothing. The script writes no archive at all and says so on
stderr instead. Cover the bucket at the bucket: object versioning plus a
lifecycle rule, or a scheduled sync of the media prefix into a second bucket held
under different credentials. Check this the first time the backup runs after
switching backends — a database restored without its images is a recovery that
looks complete and is not.

Set `OFFSITE_BUCKET` and the script also copies each file to an S3-compatible
bucket and then reads the dump back to prove it arrived. Until that is set, the
backups live on the machine being backed up, which survives a dropped table and
nothing else. Use credentials scoped to the backup prefix and distinct from the
media keys: an application that has been compromised should not be able to delete
the backups that would undo the damage.

**Drill it.** `scripts/restore-drill.sh` restores the newest dump into a
throwaway PostgreSQL container and checks the checksum, the Flyway history, the
core tables, the constraint count and one referential invariant, then destroys
the container. It is safe on a working day and it exits non-zero when the dump is
bad — which is the only way to know a backup is a backup rather than a file.
Run it monthly and before every release.

`scripts/restore.sh` is the real thing and deliberately requires
`--confirm-replace-database`. It stops the API, replaces the database, optionally
replaces media and restarts the API. Validate Flyway, readiness, object counts
and several media objects before returning traffic.

The drill does not prove the media archive matches the dump. In a real recovery
restore both and open a post that has an image. On S3 there is no archive to
restore: the images are wherever the bucket's own versioning and replication left
them, which is the thing to have verified before needing it.

For Kubernetes, prefer managed PostgreSQL automated snapshots. The supplied
manifest expects an RWX media volume; set `MEDIA_BACKEND=S3` when the cluster
cannot provide reliable shared storage.

## Monitoring and alerts

Prometheus scrapes the internal port at `/actuator/prometheus`. Important signals:

- `campusguard_moderation_cases{status=...}` — durable queue by state;
- `campusguard_moderation_sla_overdue` — cases past their review deadline;
- `campusguard_moderation_engine_degradations_total` — primary-engine failures;
- `campusguard_moderation_analysis_seconds` — analysis latency;
- Spring HTTP, JVM, database-pool and process metrics.

The provided rules alert on API downtime, a growing queue, SLA violations and a
5xx ratio above 2%.

Delivery is Alertmanager's job, and it is configured in two steps because
Alertmanager — unlike every other component here — does not expand environment
variables in its own configuration:

1. Set `ALERT_SMTP_HOST`, `ALERT_SMTP_PORT`, `ALERT_SMTP_USERNAME`,
   `ALERT_SMTP_PASSWORD`, `ALERT_FROM` and `ALERT_TO` in `.env`. Optionally set
   `ALERT_TO_CRITICAL` to page somebody separate for outages.
2. Run `scripts/render-alertmanager.sh`. It fills in
   `deploy/observability/alertmanager.yml` from the template, refuses to write
   anything if a variable is unset, checks the result with Alertmanager's own
   `amtool`, and leaves the file mode 600 because it holds an SMTP password. The
   rendered file is gitignored.

Re-run it whenever those values change, and restart the `alertmanager` service.

Use a mail path that still works when the application is the broken thing —
in practice a different provider from the password-reset mailer, and always
different credentials.

Critical alerts are notified in 10 seconds and repeated hourly; warnings wait 30
seconds and repeat every four hours. A firing `CampusGuardBackendDown` suppresses
the warnings it causes, so an outage sends one email rather than three.

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
- **Media volume full:** stop uploads at the edge, expand the volume or move to
  `MEDIA_BACKEND=S3`, verify file and database consistency, then resume. Do not
  delete database-referenced files by hand; enable the sweep instead, which only
  removes what nothing refers to.
- **Images missing after a deploy:** almost always the filesystem backend on a
  platform that does not keep the directory. Check the `Media storage is ...`
  line in the startup log and the sweep's "row(s) with no object" count. The rows
  are intact, so moving to S3 fixes every future upload; the images already lost
  are only recoverable from a media backup.
- **Database incident:** take the API read-only/offline, preserve the failed
  instance for analysis, restore into a new database, verify, then switch traffic.
