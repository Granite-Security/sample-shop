# Known Bugs

## `GET /api/delivery`: no pagination (the N+1 is gone; indexes added)

Status: **partly fixed.** The N+1 was already gone before this entry was
revisited; the missing indexes are now added. Pagination remains.

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

### 3. No pagination — **still open**

`DeliveryRepository` exposes only unbounded `findAll`/`findByStatus`/
`findByPaymentStatus`/`findByStatusAndPaymentStatus` (all `Flux`, no
`Pageable`/`LIMIT`). With no filter params, `getDeliveries` fetches every row in
the table unconditionally. This is now the actual cost of the endpoint, and
fixing it changes the API contract and both back offices — size it as its own
piece of work.

Not the cause of anything here: no blocking or synchronous I/O exists anywhere
in the reactive chain (no `RestTemplate`, `.block()` or `Thread.sleep`), so this
was never an event-loop-blocking problem.
