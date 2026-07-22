# Delivery Microservice API Refactor — Plan

Status: **planning only, nothing implemented yet.** This document exists to
think through the target API shape, the full blast radius of changing it, and
a step-by-step sequence before any code is touched.

## 1. Current state (as of this analysis)

### Routes (`delivery/src/main/java/org/granitesecurity/delivery/route/DeliveryRoute.java`)

| Method | Path | Query params | Auth |
|---|---|---|---|
| GET | `/api/delivery` | `status`, `paymentStatus` (both optional) | any authenticated user |
| GET | `/api/delivery/{orderId}` | — | any authenticated user (**no ownership check** — any logged-in user can fetch any order's delivery by ID) |
| PUT | `/api/delivery/{orderId}/status` | body `{status, description}` | `ADMIN` or `MANAGER` only |

Notably **missing** as REST endpoints (they only happen via Kafka):
- Creating a delivery — only via `OrderEventConsumer` consuming `orders.events` from `shop`.
- Updating payment status — only via `PaymentEventConsumer` consuming `payments.events` from `payment`.
- No webhook endpoint exists.

### The specific ugliness you flagged

`DeliveryService.getDeliveries(status, paymentStatus)` branches on which of
two optional query params are present:
```java
if (status != null && paymentStatus != null) { ... findByStatusAndPaymentStatus ... }
else if (status != null) { ... findByStatus ... }
else if (paymentStatus != null) { ... findByPaymentStatus ... }
else { ... findAll ... }
```
Four repository methods exist purely to cover this combinatorial branching,
and it doesn't scale — a third optional filter would mean 8 branches / 8
methods.

**Also found while mapping this out: the branching is currently dead code
from the UI's perspective.** `ui-shop`'s `DeliveryManagement.tsx` (the only
caller of the list endpoint) calls `getDeliveries()` with **no query params
at all** and does all filtering (`filterStatus`, `dateFrom`, `dateTo`)
client-side over the full list. So today, in practice, only the `findAll()`
branch is ever exercised in production — the `status`/`paymentStatus`
filtering exists in the backend but nothing calls it.

### Known correctness/perf issues (see `docs/bugs.md` for full detail)

- N+1 query in `getDeliveries`/`toResponse`: 1 query for the delivery list +
  1 query per delivery for its tracking history.
- No pagination — `findAll()` returns every row unconditionally.
- No index on `delivery.status` or `delivery.payment_status`.
- `TrackingDetailResponse` DTO exists but is never constructed or returned —
  dead code.

### Everyone who touches this API today

**Frontend (`ui-shop`)** — all calls funnel through `ui-shop/src/api.ts:111-121`:
- `getDelivery(orderId)` → `GET /api/delivery/{orderId}` — used by `OrderDetail.tsx` (single order's delivery panel) and `Orders.tsx` (builds a per-row delivery-status map for the orders list).
- `getDeliveries(params)` → `GET /api/delivery` — used by `DeliveryManagement.tsx` (admin/manager screen), called with no params, filters client-side.
- `updateDeliveryStatus(orderId, status, description)` → `PUT /api/delivery/{orderId}/status` — used by `DeliveryManagement.tsx`'s status-change actions (e.g. "Mark dispatched", "Delivery failed").
- Types mirrored in `ui-shop/src/types.ts` (`DeliveryResponse`, `TrackingEvent`, `DeliveryAddress`) — must stay in sync with any DTO change.
- Route: `ui-shop/src/App.tsx` → `/admin/deliveries` → `DeliveryManagement`, gated by `isAdmin || isManager` in the UI (matches backend's role check on the status-update endpoint, but not on the two GET endpoints, which only require `authenticated()`).

**Gateway** (`gateway/.../config/RouterConfig.java`): routes `/api/delivery/**` straight through to `delivery` with no path rewriting. Gateway's own security permits everything through; delivery enforces its own auth.

**Other microservices — no direct REST calls.** The only cross-service coupling is via Kafka:
- `shop` → `orders.events` → `delivery` (`OrderEventConsumer`) creates deliveries.
- `payment` → `payments.events` → `delivery` (`PaymentEventConsumer`) updates payment status.
- `delivery` → `delivery.events` → `shop` (`EventConsumer.onDeliveryEvent`) transitions order status (`SHIPPED` on dispatch, `DELIVERED` on delivery). **Any change to `delivery.events`'s payload shape or `eventType` values is a breaking change for `shop`, independent of the REST refactor.**

**k8s**: `k8s/base/delivery.yaml` (Service on port 8063), `k8s/base/config.yaml`'s `MICROSERVICES_DELIVERY_URI` used only by gateway.

**Tests**: no smoke tests currently reference delivery endpoints at all (checked `smoke-tests/`) — so there's no existing automated safety net for this refactor; manual verification will be the only check unless tests are added as part of this work.

## 2. Target API shape (per your direction)

- `GET /api/deliveries` → returns **all** deliveries (plural noun, no
  query-param branching soup — this becomes the simple, unconditional list).
- `GET /api/deliveries/status/{status}` → returns deliveries filtered by
  status, **only if we actually need it** — given the finding above that the
  UI currently does all filtering client-side and never sends `status`/
  `paymentStatus` today, this endpoint may not be needed at all yet. Worth
  deciding explicitly rather than carrying it forward out of habit.
- Drop `paymentStatus` filtering as a dedicated endpoint unless a real caller
  needs it — same reasoning.
- Keep `GET /api/deliveries/{orderId}` and `PUT /api/deliveries/{orderId}/status`
  (renamed to plural for consistency), unchanged in behavior otherwise.

Open question to settle before implementation: do we keep the singular
`/api/delivery/...` path as a permanent alias for backward compatibility, or
is a clean break with a coordinated frontend+backend deploy acceptable? See
§4 (impact) and §5 (rollout) below — this determines how risky the change is.

## 3. Step-by-step refactor plan

1. **Decide the exact contract** (blocking everything else):
   - Confirm final path names: `/api/deliveries`, `/api/deliveries/{orderId}`,
     `/api/deliveries/{orderId}/status`, and whether `/api/deliveries/status/{status}`
     is actually needed (recommend: skip it initially, add later if a real
     caller needs server-side filtering — YAGNI, since nothing calls it today).
   - Confirm response DTO stays the same shape (`DeliveryResponse`) — no
     reason to change it as part of this refactor unless something else
     requires it.
   - Decide the dead `TrackingDetailResponse` DTO's fate: delete it, or wire
     it into a genuinely new "tracking detail" endpoint if there's a real
     use case. Otherwise it's just noise.

2. **Backend: replace the if/else branching.**
   - Replace the 4-way branch + 4 repository methods with a single
     `Criteria`/query-spec approach (Spring Data R2DBC supports
     `R2dbcEntityTemplate` + `Criteria.where(...)` composition), or — simpler,
     given only 0-2 optional filters exist and the UI doesn't currently use
     them — just keep `findAll()` as the only list method and drop the
     filtered variants entirely until a real need reappears. Recommend the
     latter: don't build filtering infrastructure for filters nothing calls.
   - Fold in the already-documented N+1 fix (batch tracking lookup) and the
     missing indexes (`docs/bugs.md`) while touching this code anyway, since
     it's the same method.

3. **Backend: rename routes.**
   - Update `DeliveryRoute.java` path definitions from `/api/delivery` to
     `/api/deliveries`.
   - Update `DeliverySec.java`'s security matcher (`/api/**` currently — check
     it still matches the new plural path; likely no change needed since
     it's a broad prefix, but verify explicitly).

4. **Gateway.**
   - Update `RouterConfig.java`'s route predicate from `.path("/api/delivery/**")`
     to `.path("/api/deliveries/**")`.

5. **Frontend (`ui-shop`).**
   - Update all paths in `api.ts` (`getDelivery`, `getDeliveries`,
     `updateDeliveryStatus`) to the new plural paths.
   - No DTO/type changes expected in `types.ts` if the response shape is kept
     identical (per step 1's decision).
   - No component changes expected beyond the `api.ts` layer, since
     `DeliveryManagement.tsx`/`OrderDetail.tsx`/`Orders.tsx` all go through
     that shared API wrapper rather than hardcoding paths themselves.

6. **k8s / gateway config.**
   - No Service/Deployment changes needed — the path change is internal to
     the gateway's routing rule and the backend's own route definitions, not
     the Service/port wiring. Only `RouterConfig.java` (step 4, application
     code, not a k8s manifest) changes.

7. **Verification** (no automated tests currently exist for this — see §1):
   - Manual pass through `DeliveryManagement.tsx` (list, status update),
     `OrderDetail.tsx` (single delivery view), `Orders.tsx` (delivery-status
     column) against the new paths, in kind first, then Hetzner.
   - Confirm the Kafka-driven paths (`OrderEventConsumer`,
     `PaymentEventConsumer`, and `delivery`'s own outbox → `shop`) are
     **unaffected** — none of them go through the REST paths being renamed,
     but worth an explicit regression check on a full order → payment →
     delivery → shipped flow, since that's the one thing that could silently
     break if any of this is wired more tightly to the REST layer than this
     analysis assumes.
   - Consider adding smoke-test coverage for the delivery endpoints as part
     of this work, given none exists today.

## 4. Impact summary — who/what needs to change

| Area | File(s) | Change needed |
|---|---|---|
| Backend routes | `delivery/.../route/DeliveryRoute.java` | path rename |
| Backend service | `delivery/.../service/DeliveryService.java` | drop branching, batch N+1 fix |
| Backend repository | `delivery/.../repository/DeliveryRepository.java` | drop unused filtered-query methods (or keep if §1 decides to retain filtering) |
| Backend security | `delivery/.../security/DeliverySec.java` | verify matcher still applies (likely no change) |
| DB migration | `delivery/.../db/changelog/` | new changelog for status/payment_status indexes |
| Gateway | `gateway/.../config/RouterConfig.java` | path predicate rename |
| Frontend API layer | `ui-shop/src/api.ts` | path rename in 3 functions |
| Frontend types | `ui-shop/src/types.ts` | none expected (DTO shape unchanged) |
| Frontend pages | `DeliveryManagement.tsx`, `OrderDetail.tsx`, `Orders.tsx` | none expected (go through `api.ts`) |
| Cross-service (Kafka) | `shop`'s `EventConsumer.onDeliveryEvent`, `delivery`'s `OrderEventConsumer`/`PaymentEventConsumer` | none — unaffected by the REST path rename, but should be regression-checked (see §3.7) |
| k8s manifests | none | no Service/port changes needed |
| Docs | `docs/bugs.md`, `docs/improvements.md` | update once fixes land, cross-reference this doc |

## 5. Rollout risk — the one thing that needs a decision

Renaming `/api/delivery` → `/api/deliveries` is a **breaking path change**
touching both gateway routing and the frontend simultaneously. Because the
frontend and backend deploy as separate images, there's a window where an
old `ui-shop` build could hit a new `delivery`/gateway that no longer answers
the old path (or vice versa) unless deployed together atomically. Options:
- **Clean break, coordinated deploy**: rebuild and redeploy gateway, delivery,
  and ui-shop together in the same maintenance window. Simplest, matches how
  changes have been rolled out so far in this project (see `cloudify.md`),
  acceptable given this is pre-production/demo-scale traffic.
- **Temporary dual-routing**: keep both `/api/delivery/**` and
  `/api/deliveries/**` accepted by the gateway/backend for a transition
  period, remove the old one later. More resilient but adds temporary
  complexity for a problem (mixed old/new clients) that likely doesn't exist
  at this project's current scale.

Recommendation: clean break with a coordinated deploy, given the small blast
radius (one frontend, one admin-only page, no external API consumers) and
that we're already doing manual coordinated deploys for every other change in
this project.
