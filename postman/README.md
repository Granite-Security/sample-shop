# Postman — accounting

A collection for the `accounting` service (8068): the journal entries derived from domain
events, and the endpoints that prove they add up. Background is in
[`docs/finance/accounting.md`](../docs/finance/accounting.md).

```
granite-accounting.postman_collection.json   the requests and their tests
granite-local.postman_environment.json       gateway on localhost:8080
granite-hetzner.postman_environment.json     https://granite-security.org
```

Import all three, pick an environment, get a token, run the collection.

## Getting a token

Everything here needs **ROLE_ADMIN**. The collection is preconfigured for the
authorization-code flow against the `oidc-client` registration, which already carries
Postman's callback URL (`https://oauth.pstmn.io/v1/callback`) in `auth-server`'s
`SecurityConfig` — that redirect URI exists for exactly this.

1. Open the collection → **Authorization** → **Get New Access Token**.
2. Log in as `admin` / `admin`. (`manager` / `manager` also works — it holds `ROLE_ADMIN`
   today. `user` / `user` will authenticate fine and then get a 403, which is the point of
   the *Access control* folder.)
3. **Use Token.**

Two things that look like bugs and are not:

- **A restart of `auth-server` invalidates every token.** Its RSA key pair is generated
  fresh on each startup, by design. A sudden 401 across the whole collection usually means
  auth-server restarted, not that anything broke — get a new token.
- **`clientSecret` is `secret`.** That is the default in `application.yaml`
  (`{noop}secret`) and it is a local-development value. If the deployment overrides
  `app.oauth2.gateway-client.secret`, put the real one in the environment rather than in
  the collection file.

## What each request checks

The tests assert the invariants, not just the status code — a 200 on `/trial-balance` that
does not balance is a worse outcome than a 500.

| Request | The assertion that matters |
|---|---|
| `GET /accounts` | The chart is seeded, codes are unique, and `4100` is still a **debit-side contra** account. If that flips, gifted credit stops reducing revenue and starts inflating it. |
| `GET /journals` | **Every entry balances**, and no line carries both a debit and a credit. Estimated entries state their assumptions (D21). |
| `GET /trial-balance` | Debits equal credits for the period, and no column is negative — debits and credits are magnitudes, so a negative one means a sign went somewhere it should not have. |
| `GET /reconcile` | No unbalanced journals. Reports unposted facts as information, not failure — see below. |
| `GET /credit-loss` | The bands sum to the reported allowance, each band applies its own rate, and the allowance never exceeds the exposure. Also that it is **labelled an estimate** with the date its rates were set — an assumed number rendered like a measured one is this design's stated failure mode. |
| `POST /periods/{code}/estimates` | Accepts 200 or 409. Run it twice: the second run must change nothing, because estimates are idempotent per period. |
| `GET /periods` | Every period is OPEN or CLOSED, and a closed one records when and by whom. Sets `oldestOpenPeriod` for the close request. |
| `POST /periods/{code}/close` | Accepts 200 **or** 409: already closed, or still holding unposted facts, are both legitimate answers. |
| Access control | No token → 401. A `ROLE_USER` token → 403. If either returns 200, the service is unprotected. |

## Reading the results

**An empty journal is a correct answer.** The books contain only what events have produced
since `accounting.books-open-on` (default `2026-09-01`). A fresh deployment has no entries
and a trial balance of all zeros. That is not a failure — it is D22: the books start on a
date, and there is no honest way to book a past that was never booked.

**`unpostedFacts > 0` is usually fine.** Four topics means out-of-order delivery is
guaranteed, so a delivery can be consumed before the order it delivers. Such a fact waits
and is swept every 30 seconds. Re-run `/reconcile`; if the count does not fall, something
is waiting on a prerequisite that is never coming — that is the real signal.

**The loss rates are invented.** There is no repayment history to derive them from, so
`/credit-loss` returns `estimated: true` and an `asOf` date, and every journal built on them
carries the assumption set it used. If the allowance ever looks large, the fix is a credit
limit in balance's `CreditPolicy`, not a change to the rates: a high expected default means
the platform is giving stock away, which is a product bug and not a reporting one.

**Some events correctly produce no journal.** A gift is not booked at all (IFRS 15.72,
§2.4), a failed payment moves nothing, and a payment for a balance-funded order is skipped
because balance's `Spent` event carries the funding split and posts all three legs. Each of
those is recorded as a fact marked `IGNORED` with its reason, so "why is there no entry for
this?" always has an answer.

## Making something appear

The *Drive the books* folder has the cheapest starting point — an admin gift — and it
deliberately demonstrates a **non**-posting: the gift produces no journal, only a memo.

To see the interesting entry, the full path is: gift credit → place an order paid from
balance → deliver it. Then `/journals` shows revenue credited **gross** with the gifted
part debited to `4100`, so the discount stays visible instead of being netted away. Those
first two steps go through `shop` and `payment` and are not in this collection.

## Reaching the service directly

The collection goes through the gateway (`/api/accounting/**`), which is how the service is
actually reached. To talk to the pod instead — for Swagger, or to rule the gateway out:

```bash
kubectl -n granite port-forward deploy/accounting 8068:8068
```

Then set `baseUrl` to `http://localhost:8068`. The endpoints still need a real JWT; only
the network path changes. Swagger UI is at `/swagger-ui.html` on that port and has no
HTTPRoute in any overlay — it should not get one.
