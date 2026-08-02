# Actuator + readiness probes

Status: **proposed, not started** · 2026-08-02

## Why

Every deploy takes the platform down for about ninety seconds, and it is not
subtle: `/api/**` returns 500 for the whole window. Measured on four consecutive
rollouts on 2026-08-01, and reproduced deliberately afterwards.

The cause is a missing readiness probe, and the trap is that **a missing probe
does not mean "no check" — it means "always ready."** kubelet marks a container
Ready as soon as the process is *running*. Running means the JVM started, not
that Spring Boot finished, and these services take ~90s to boot (`shop` logs
`Started ShopApplication in 94.29 seconds`).

So on every rollout, with one replica and `maxUnavailable=0` / `maxSurge=1`:

1. New pod created → Ready **immediately**, because nothing says otherwise
2. Kubernetes sees a healthy replacement → terminates the old pod
3. The Service's EndpointSlice now points only at the new pod
4. Nothing listens on the port for ~90s → connection refused → **500s**

Restarting the gateway appeared to fix this each time. It did not — by then
~2 minutes had passed and the new pod had finished booting.

More replicas would only dilute this, not fix it: the new pod still enters the
Service immediately, so a share of requests still hit a booting JVM.

### Current state

| Service | readiness | liveness |
|---|---|---|
| `gateway` | `/` | `/` |
| `auth-server` | `/auth/login` | `/auth/login` |
| `shop`, `payment`, `delivery`, `profile`, `notification`, `greetings`, `ui-shop` | **none** | **none** |

And **no service has `spring-boot-starter-actuator` at all.** The
`/actuator/health` that answers on `payment` is a hand-rolled functional route
(`PaymentRoute`: `.GET("/actuator/health", healthHandler::health)`); `shop`'s 401
on the same path is just its security chain rejecting an unmapped URL. The
actuator-shaped URLs across this repo are not actuator.

## What to do

Per JVM service (`shop`, `payment`, `delivery`, `profile`, `notification`,
`greetings`, and `gateway`/`auth-server` to replace their ad-hoc paths):

1. Add `spring-boot-starter-actuator`.
2. `management.endpoints.web.exposure.include: health` — health only. Nothing
   else goes on the wire.
3. Permit `/actuator/health/**` in the security chain. Probes arrive from
   kubelet unauthenticated, which is why `shop` currently answers 401.
4. Manifests: `readinessProbe` → `/actuator/health/readiness`,
   `livenessProbe` → `/actuator/health/liveness`.

`ui-shop` and `ui-demo` are nginx — an `httpGet /` probe is enough.

### The decision that actually matters

**Point readiness at the `readiness` group, never at full `/actuator/health`.**

The readiness group defaults to `readinessState` — the application's own
lifecycle. Full `/actuator/health` aggregates dependency indicators: R2DBC,
Kafka, and so on. Probe the full endpoint and a Postgres blip makes *every pod
unready simultaneously*, so Kubernetes pulls the entire service out of the load
balancer and turns a recoverable hiccup into a total outage. The probe groups
exist precisely to avoid that; Spring Boot auto-enables them when it detects
Kubernetes.

Keep the rich dependency view on `/actuator/health` for humans and dashboards.

### Two things this tidies up

- **`payment`'s hand-rolled routes collide.** `/actuator/health` is a
  `RouterFunction` today and must be removed. Better: turn
  `/actuator/health/providers` into a proper `HealthIndicator`, so provider
  status appears inside `/actuator/health` automatically instead of on a bespoke
  path — less code, and it composes.
- The platform gains real dependency health, which it has nowhere today.

## Alternative considered: `tcpSocket`

A `tcpSocket` probe on the service port needs no dependency, no config and no
security change, and succeeds exactly when Netty binds — a good proxy for
"booted". It was rejected as the *destination* because it only proves the port
is open, not that the app works: with Postgres down the port still accepts and
the pod is marked Ready.

It remains a reasonable stopgap if the outage needs stopping before the
actuator work is scheduled, and swapping it for `httpGet` later is a one-line
manifest edit per service.

## Sequencing

Do `shop` and `payment` first, then prove it: restart a backend pod and poll
`/api/**` across the whole rollout, expecting zero non-200s. That is the test
that disproved the previous theory (stale gateway connections), so it is the one
that should confirm this fix. Roll the pattern out to the rest once it holds.

## Related, not required

- **`auth-server` sessions are in-memory.** A restart drops every session, and
  users mid-login get a 403 on submit: the CSRF token dies with the session. The
  same happens on ordinary session expiry with the login page left open. An
  invalid CSRF on `/auth/login` would be better redirected back to a fresh login
  page than surfaced as a bare 403.
- Signing keys are regenerated on every `auth-server` start (`CLAUDE.md`), so a
  restart already invalidates issued JWTs. Persistent sessions would need to be
  considered alongside that, not separately.
