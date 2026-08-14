# Known Bugs

## `GET /api/delivery` was slow — closed

Status: **fixed.** The N+1 was already gone before this entry was revisited; the
missing indexes and pagination have since landed. Kept as a record of what was
actually wrong, because two of the three original claims were not.

**History.** This entry originally described three compounding issues in
`delivery/src/main/java/org/granitesecurity/delivery/service/DeliveryService.java`:
an N+1 query, no pagination, and missing indexes. Only the last two were still
real when it was re-read on 2026-08-14.

### 1. N+1 query — **already fixed**, in `866394c` "refactoring delivery"

`toResponse` no longer touches the database. It is a pure field-mapper, and
`getDeliveries` is one query plus `.map(this::toResponse)` — a single round
trip regardless of row count. The only remaining tracking lookup is in
`getTrackingDetail`, which is one delivery to one tracking query and correct as
written.

The description here lagged the code by several commits. Re-read the method
before trusting a performance claim in this file.

### 2. Missing indexes — **fixed**

`delivery.status` and `delivery.payment_status` had no index, so all three
filtered paths (`findByStatus`, `findByPaymentStatus`,
`findByStatusAndPaymentStatus`) did a sequential scan. Added in
`db/changelog/004-index-delivery-status.sql` as two single-column indexes
rather than one composite: a composite `(status, payment_status)` leaves the
payment-status-only query uncovered and would need a second index anyway,
whereas two single-column indexes serve each single-filter query directly and
can be bitmap-ANDed for the two-filter case.

Expect no measurable win at current table sizes — the planner will still choose
a sequential scan on a small table. The index is the cheap half of the fix and
earns its keep as the table grows, not today.

### 3. No pagination — **fixed**

`DeliveryRepository` exposed only unbounded finders (all `Flux`, no
`Pageable`/`LIMIT`), so `getDeliveries` fetched every row in the table
unconditionally. This was the real cost of the endpoint — the N+1 never was.

`GET /api/delivery` now takes `page`/`size` (size clamped to 100) and returns a
`PagedResult`, matching what `shop` already does for orders and the catalog.
`DeliveryQueryRepository` builds the query over an `R2dbcEntityTemplate` because
both the filters and the sort are chosen per request, and `ORDER BY` cannot be a
bind parameter — the sort column comes from an allow-list, never from the caller's
string.

**The filters had to move with it.** Both back offices filtered by status and
date and sorted in the browser, over "every row in the table". Paginate without
moving those server-side and each one silently starts filtering one page — which
reads as data going missing, not as a filter. `status`, `paymentStatus`, a
half-open `from`/`to` window and `sort`/`dir` are all server-side now, and
`delivery.created_at` is indexed for the range scan
(`005-index-delivery-created-at.sql`).

The response shape changed from `DeliveryResponse[]` to `PagedResult`, so both
SPAs must deploy alongside the service.

Not the cause of anything here: no blocking or synchronous I/O exists anywhere
in the reactive chain (no `RestTemplate`, `.block()` or `Thread.sleep`), so this
was never an event-loop-blocking problem.
