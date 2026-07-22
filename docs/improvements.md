# Known Improvements / TODOs

## Stale pooled R2DBC connections (shop, payment, profile)

**Status:** fixed for `delivery` only. Still open for `shop`, `payment`, `profile`.

**Symptom:** intermittent 500s on reactive endpoints backed by R2DBC (e.g. was
observed on `GET /api/delivery`), self-healing shortly after — a refresh or
retry works. In the logs this shows up as:
- Gateway: `reactor.netty.http.client.PrematureCloseException: Connection
  prematurely closed DURING response`, sometimes with the response already
  committed (200 OK) before it died mid-stream.
- The affected service's own logs: `io.r2dbc.postgresql.client.ReactorNettyClient
  $PostgresConnectionException: [08006] An I/O error occurred ... Caused by:
  Connection reset by peer`.
- The corresponding Postgres pod's logs: `could not receive data from client:
  Connection reset by peer` — confirming the *client* side (the app pod) is
  the one whose TCP connection died, not Postgres actively closing it.

**Root cause:** none of `delivery`/`shop`/`payment`/`profile`'s
`application.yaml` configure any R2DBC pool validation or idle-time settings.
Spring Boot's default R2DBC pool will hand out a connection that's been idle
for a while without checking it's still alive. On this single-node cluster,
an idle pod-to-pod TCP connection routed through Calico can get silently
dropped from the kernel's connection-tracking table after a period of
inactivity — the pool doesn't know this happened, hands the stale connection
to the next request, and the request only discovers it's dead (reset) when it
actually tries to use it. Since these endpoints stream a reactive `Flux`
straight from R2DBC, a broken DB connection mid-query kills the in-flight
HTTP response too, which the gateway then surfaces as a 500 or a
premature-close. The pool self-heals on the *next* request by creating a
fresh connection — which is exactly why the failure is intermittent and a
retry/refresh "just works."

**Fix already applied to `delivery`** (`delivery/src/main/resources/application.yaml`):
```yaml
spring:
  r2dbc:
    pool:
      enabled: true
      validation-query: SELECT 1
      max-idle-time: 5m
```
This makes the pool proactively validate/discard stale connections instead of
handing them to a live request.

**TODO:** apply the identical block to `shop/src/main/resources/application.yaml`,
`payment/src/main/resources/application.yaml`, and
`profile/src/main/resources/application.yaml` — they all share the exact same
bare R2DBC config and are equally exposed to this failure mode, just not yet
observed/reported for those three.
