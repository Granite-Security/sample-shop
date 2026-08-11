# Going live with Stripe

Switching the production cluster from Stripe **test mode** to **live mode**. Three
values change, in one Secret, and three pods have to be restarted to read them.

Nothing here is managed by ArgoCD — `granite-secrets` is applied by hand and carries
no ArgoCD tracking label, so a sync will never overwrite it and never apply it either.
Every step below is a manual `kubectl` against the Hetzner cluster.

---

## 0. Before you touch anything

- **Activate the Stripe account.** Live keys exist as soon as the account is created,
  but charges fail until Stripe has completed the account activation/verification.
- **Check the currency.** `STRIPE_CURRENCY: "chf"` in `k8s/base/config.yaml`. The live
  account must be able to settle CHF. If it can't, that ConfigMap value changes too
  (and payment needs the same restart).
- **Get all three live values in hand before starting** — see §1. Doing the key swap
  without the matching live webhook secret leaves webhook verification failing on
  every delivery.
- Confirm you're on the right cluster:

  ```bash
  kubectl config current-context   # must read davide-hetzner-admin
  ```

---

## 1. Collect the three live values from the Stripe dashboard

Flip the dashboard's **Test mode** toggle **off** first — live and test are separate
worlds, including separate webhook endpoint lists.

| Value | Where | Looks like |
|---|---|---|
| Publishable key | Developers → API keys | `pk_live_…` |
| Secret key | Developers → API keys → *Reveal live key* | `sk_live_…` |
| Webhook signing secret | Developers → Webhooks → **register a new endpoint in live mode** | `whsec_…` |

**The webhook endpoint must be created again in live mode.** The test-mode endpoint
does not carry over, and its `whsec_` is not valid for live events. Register:

```
https://sichocolate.com/api/payments/webhook/stripe
```

Select exactly these events — everything else the adapter ignores:

`payment_intent.succeeded`, `payment_intent.payment_failed`, `payment_intent.canceled`,
`refund.created`, `refund.updated`, `refund.failed`

Then copy the new signing secret from the endpoint's page (*Signing secret* → Reveal).

**On the domain.** Both storefronts' nginx proxies `/api/` to the same `gateway:8080`,
so `sichocolate.com/...` and `granite-security.org/...` reach the identical payment pod,
handler and database — the webhook is a server-to-server call from Stripe and the
shopper's domain is irrelevant to it. Register **sichocolate.com**, because that is the
domain real money runs through: a webhook pinned to granite-security.org breaks silently
if that domain is ever retired or its cert lapses, and orders quietly stop reaching
`PAID`. Do **not** register both URLs — Stripe would deliver every event twice. The
`provider_event` table dedupes on event id so nothing double-charges, but it is pure
noise.

The secret key is shown once. Copy it somewhere before leaving the page.

---

## 2. Update the source-of-truth file

The live values live in a gitignored file on your machine:

```
k8s/hetzner/app-multi/secrets-patch.yaml
```

(`app-multi` is the overlay ArgoCD deploys. `app/` and `app-chocolate/` are the older,
mutually exclusive overlays — if you keep their `secrets-patch.yaml` around, update
them too so they don't resurrect test keys later.)

Edit these three lines:

```yaml
  stripe-publishable-key: "pk_live_…"
  stripe-secret-key: "sk_live_…"
  stripe-webhook-secret: "whsec_…"      # the LIVE endpoint's secret, from §1
```

Do not commit this file — it is gitignored, deliberately. `secrets-patch.yaml.example`
is the tracked copy and must stay free of real values.

---

## 3. Apply to the cluster

`secrets-patch.yaml` is not listed in `kustomization.yaml`, so `kubectl apply -k` does
**not** apply it — you apply the file directly. Despite its name it is a complete
`granite-secrets` Secret (it carries every key the base Secret does, not just the three
you edited), so applying it replaces the whole Secret and nothing is pruned.

It has no `namespace:` in its metadata, hence the `-n granite`.

**Always diff first.** This shows exactly which keys will change, and catches the real
hazard: the file also carries DB passwords, Garage and PayPal credentials. If any of
those were ever rotated directly on the cluster and not written back into this file,
applying it silently reverts them — and a reverted `db-*-password` breaks Postgres auth
for every service, since `production-patches.yaml` wires Postgres itself to this same
Secret.

```bash
kubectl config current-context   # must read davide-hetzner-admin
kubectl -n granite diff -f k8s/hetzner/app-multi/secrets-patch.yaml
```

You want to see *only* the three `stripe-*` keys change. Secret values show as base64 in
the diff — decode anything you want to read with `base64 -d`. If a key you did not touch
appears, stop and reconcile the file with the cluster before going further.

Then apply:

```bash
kubectl -n granite apply -f k8s/hetzner/app-multi/secrets-patch.yaml
```

Verify what landed (prefixes only — don't print whole secret keys):

```bash
for k in stripe-publishable-key stripe-secret-key stripe-webhook-secret; do
  printf '%s: ' "$k"
  kubectl -n granite get secret granite-secrets -o jsonpath="{.data.$k}" \
    | base64 -d | cut -c1-12; echo
done
```

Expect `pk_live_`, `sk_live_`, `whsec_`. If you still see `_test_`, the apply didn't take.

> If you ever need to change the keys *without* touching anything else in the file — say
> you're on a machine where `secrets-patch.yaml` isn't up to date — patch the three keys
> in place instead. This can't affect any other key, but it does mean pasting the values
> on the command line (and into your shell history):
>
> ```bash
> kubectl -n granite patch secret granite-secrets --type merge -p '{
>   "stringData": {
>     "stripe-publishable-key": "pk_live_…",
>     "stripe-secret-key": "sk_live_…",
>     "stripe-webhook-secret": "whsec_…"
>   }
> }'
> ```
>
> Write the same values back into `secrets-patch.yaml` afterwards, or the next
> `apply -f` reverts them.

---

## 4. Restart the pods that read them

Secret values are injected as env vars (`secretKeyRef`), and env vars are fixed for
the life of a container — **a Secret change alone changes nothing running**. Restart:

| Pod | Reads | Why |
|---|---|---|
| `payment` | `stripe-secret-key`, `stripe-webhook-secret` | `StripeConfig` sets `Stripe.apiKey` once at `@PostConstruct`; the webhook secret verifies signatures |
| `ui-shop` | `stripe-publishable-key` | rendered into `/config.js` by the entrypoint at container start |
| `ui-demo` | `stripe-publishable-key` | same entrypoint mechanism, second storefront |

```bash
kubectl -n granite rollout restart deploy/payment deploy/ui-shop deploy/ui-demo
kubectl -n granite rollout status deploy/payment  --timeout=180s
kubectl -n granite rollout status deploy/ui-shop  --timeout=120s
kubectl -n granite rollout status deploy/ui-demo  --timeout=120s
```

`gateway` and `shop` do not read Stripe keys — no restart needed. (If you also changed
`STRIPE_CURRENCY` in the ConfigMap, `payment` picks that up in the same restart.)

---

## 5. Verify

**Front end** — the publishable key is public, so just read it back:

```bash
curl -s https://sichocolate.com/config.js       | grep -i stripe   # ui-demo
curl -s https://granite-security.org/config.js  | grep -i stripe   # ui-shop
```

Both must show `pk_live_` — the two storefronts read the same secret key, so they go
live together whether you intended that or not. Hard-reload the browser (Cmd+Shift+R) before testing checkout —
an old `config.js` in the browser cache still holds the test key and produces a
confusing "test key with live intent" error.

**Back end** — place one small real order end to end (a genuine card, a genuine charge;
test cards like `4242…` are rejected by a live key). Watch payment while you do it:

```bash
kubectl -n granite logs -f deploy/payment
```

You want to see the intent created, then the webhook arrive and verify. Then confirm
the order reached `PAID`, and confirm the charge appears in the Stripe dashboard with
**Test mode off**.

**Webhook health**: Stripe dashboard → Developers → Webhooks → the live endpoint →
recent deliveries. All 2xx. A run of 400s means the `whsec_` in the cluster doesn't
match this endpoint — recheck §1/§3.

Refund that first live order from the Stripe dashboard once you're satisfied.

---

## 6. What breaks, and what to expect

- **Orders left pending from test mode.** Their stored `pi_…` ids belong to the test
  account and don't resolve against a live key. `/api/payments/intent/{orderId}/sync`
  on those orders will fail, and their webhooks will never arrive. Cancel them, or
  leave them to expire — don't try to migrate them.
- **The comment in `k8s/base/config.yaml`** documents the test-mode endpoint
  registration ("registered in the Stripe dashboard 2026-08-02", on granite-security.org).
  Update it to record the live endpoint's date *and its new domain* — it's the only place
  that history is written down, and the domain now differs from what it says.
- **API version.** The SDK (`com.stripe:stripe-java:32.2.0`, `payment/build.gradle.kts`)
  pins outbound calls to `2026-05-27.dahlia`. Webhook payloads are rendered at the version
  set on the *endpoint*, which defaults to the account default and may be older — that's
  why `StripePaymentProvider` still maps the pre-2024 `charge.refund.updated`. Check the
  version on the new live endpoint and compare it to the test one, so live doesn't deliver
  a different payload shape than you've been testing against.
- **PayPal is independent.** `PAYPAL_ENV: "sandbox"` in `k8s/base/config.yaml` is still
  sandbox; going live on Stripe does not touch it. If you want PayPal live too, that's
  a different app's credentials plus `PAYPAL_ENV: "live"` — see `docs/payment/paypal.md`.
- **The balance provider** is unaffected.

---

## 7. Rolling back to test mode

Same three steps, in reverse: put the `pk_test_` / `sk_test_` / test-endpoint `whsec_`
values back in `secrets-patch.yaml`, apply the same `kubectl patch`, restart the same
three deployments. Real charges already taken stay taken — refund them in the live
dashboard, they will not appear in test mode.
