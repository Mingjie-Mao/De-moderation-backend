# Kubernetes deployment

`campusguard.yaml` is a production-oriented template. Before applying it:

1. Replace both image names with immutable release tags or digests.
2. Create `campusguard-secrets` through the cluster secret manager; do not apply
   the placeholder secret from source control.
3. Point `DB_HOST` at a managed PostgreSQL 16 service with automated snapshots.
4. Set the two DNS names, CORS/public URLs and certificate issuer.
5. Supply an RWX storage class for media, or replace the filesystem media
   adapter with object storage.
6. Apply only after `mvn verify`, the Android build, the admin web build and the
   k6 smoke thresholds pass for the exact image digest.

The management port is not in the Ingress and its NetworkPolicy only admits the
monitoring namespace. Add a `ServiceMonitor` in that namespace if using the
Prometheus Operator.
