# R2DBC connections without validation (and why that's scary)

**TL;DR:** Calico drops long-idle inter-pod connections silently. Validating
a pooled connection before you use it costs a couple of milliseconds — cheap
insurance against a hard-to-explain 500.

We had an order stuck at `PENDING` forever, and separately, a profile page
that 500'd once and then worked fine on retry/refresh. Both showed the same error
in the logs:

```
io.netty.channel.unix.Errors$NativeIoException: recvAddress(..) failed with error(-104): Connection reset by peer
org.springframework.dao.DataAccessResourceFailureException: ... Connection reset by peer
```

Nothing crashed. No pod restarted (in one case) or the restart was routine
and unrelated (in the other). It just intermittently failed, then healed
itself on the next request. That "heals itself" part is the part to be
suspicious of, not reassured by.

## Root cause: a connection pool that never checks

Spring Boot's default R2DBC pool keeps a handful of already-open TCP
connections to Postgres around and hands them out on request, instead of
opening a fresh connection every time. Good for performance — but by
default it does **zero liveness checking**. It assumes a connection that
was open a moment ago is still open now, and just gives it to your query.

On Kubernetes that assumption doesn't hold indefinitely. Two ways it broke
here:

1. **Calico silently drops idle inter-pod connections.** Calico (our CNI)
   routes and polices pod-to-pod traffic largely through the kernel's
   `conntrack` table, which has finite size. Connections that go quiet for a
   while are eviction candidates — especially on a small single-node VPS
   under memory pressure. When an entry gets evicted, packets on that
   "still open" connection start silently failing. Neither side gets an
   error *at that moment* — only the next time someone actually tries to use
   it.
2. **A Postgres pod got replaced.** A routine `kubectl apply` rolled
   `postgres-profile`'s pod. The old container
   exited cleanly, a new one took its place — normal Kubernetes behavior.
   But `profile`'s own pod wasn't restarted, so its pool kept handing out
   sockets pointing at a container that no longer existed.

Different triggers, same gap: the pool never verified a connection before
using it, so the first request to touch a bad one paid for it with a 500.
The *next* request created a brand-new connection (the create path was
never broken), which is exactly why it looked like it "fixed itself."

## The fix — two lines in `application.yaml`

**Before** (`payment/src/main/resources/application.yaml`, and identically
in `shop`/`profile`):

```yaml
spring:
  r2dbc:
    url: ${PAYMENT_R2DBC_URL:r2dbc:postgresql://localhost:5434/paymentdb}
    username: ${PAYMENT_R2DBC_USERNAME:myuser}
    password: ${PAYMENT_R2DBC_PASSWORD:secret}
```

**After:**

```yaml
spring:
  r2dbc:
    url: ${PAYMENT_R2DBC_URL:r2dbc:postgresql://localhost:5434/paymentdb}
    username: ${PAYMENT_R2DBC_USERNAME:myuser}
    password: ${PAYMENT_R2DBC_PASSWORD:secret}
    pool:
      enabled: true
      validation-query: SELECT 1
      max-idle-time: 5m
```

- `validation-query: SELECT 1` — run this trivial query before handing a
  pooled connection to your code. If it fails, the pool quietly discards it
  and opens a fresh one *before* your real query ever touches a dead socket.
  The caller never sees it — a few extra ms, no 500.
- `max-idle-time: 5m` — proactively recycle connections idle longer than
  5 minutes, so the pool doesn't accumulate exactly the long-idle
  connections `conntrack` is most likely to have already forgotten.


## The lesson

Any pool that hands out connections without validating them will eventually
serve a dead one — Kubernetes gives you plenty of ways for a connection to
die quietly (CNI conntrack eviction, pod rescheduling, rolling deploys).
It's a two-line fix per service, and it's a lot cheaper to add now than to
debug through it during a customer's checkout.
