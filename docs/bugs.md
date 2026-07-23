# Known Bugs (not fixed — code changes out of scope for this deployment work)

## `GET /api/delivery` is slow: N+1 query + missing indexes



**Symptom:** the deliveries list loads correctly but is noticeably slower
than it should be, and gets worse as the `delivery`/`delivery_tracking`
tables grow.

**Root cause (two compounding issues), in `delivery/src/main/java/org/granitesecurity/delivery/service/DeliveryService.java`:**

1. **N+1 query pattern.** `getDeliveries` (lines 55-67) fetches the delivery
   rows with one query, then `.flatMap(this::toResponse)` runs a **separate**
   tracking-history query per delivery — `toResponse` (lines 100-126) calls
   `trackingRepository.findByDeliveryIdOrderByTimestampDesc(delivery.getId())`
   for each row individually. For N deliveries that's `1 + N` round trips to
   Postgres.
2. **No pagination.** `DeliveryRepository` only exposes unbounded
   `findAll`/`findByStatus`/`findByPaymentStatus`/`findByStatusAndPaymentStatus`
   (all return `Flux`, no `Pageable`/`LIMIT`). With no filter params,
   `getDeliveries` fetches every row in the table unconditionally, on top of
   the N+1 cost above.
3. **Missing indexes.** `delivery.status` (defined in
   `db/changelog/001-create-delivery-schema.sql`) and `delivery.payment_status`
   (added in `003-refactor-delivery.sql`) have no index, so any filtered
   listing does a sequential scan. Only `order_id` and
   `delivery_tracking.delivery_id` are indexed.

Not the cause: no blocking/synchronous I/O found anywhere in the reactive
chain (no `RestTemplate`/`.block()`/`Thread.sleep`), so this isn't an
event-loop-blocking problem — it's purely I/O round-trip count and missing
indexes.

**Suggested fix (not applied):**
- Batch the tracking-history lookup: collect the delivery IDs from the first
  query, issue a single `findByDeliveryIdIn(...)` (ordered), group results in
  memory per delivery, and build responses from that — one query total
  instead of `1 + N`.
- Add indexes on `delivery.status` and `delivery.payment_status` via a new
  Liquibase changelog.
- Consider pagination on the list endpoint once the table grows enough that
  fetching all rows becomes the bottleneck (out of scope for now — would
  require an API/frontend contract change, not just an internal fix).

**Why this matters more on the Hetzner deployment specifically:** the same
code runs in kind, but table sizes there are tiny (demo/test data), so the
N+1 cost is invisible. On a longer-running deployment with real accumulated
data the query count and missing indexes start to actually matter, which is
likely why it was only noticed here.
