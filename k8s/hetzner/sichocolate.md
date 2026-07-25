# Adding a second front-end skin: sichocolate.com (ui-demo)

**Status: live.** `sichocolate.com` is deployed and serving `ui-demo` on the
Hetzner cluster as of 2026-07-22 — DNS, CoreDNS, the cutover, and verification
(§3-§5, §7) are all done. Still open: the Google OAuth redirect URI
(`https://sichocolate.com/login/oauth2/code/google`) hasn't been registered yet,
so Google login will fail on this domain until that's added in Google Cloud
Console (form login still works); the Stripe webhook (§6) is intentionally
skipped since it isn't used.

Goal: run the exact same backend (auth-server, gateway, greetings, shop, payment,
profile, delivery, kafka, postgres) behind a **second domain**, `sichocolate.com`,
serving the `ui-demo` front end instead of `ui-shop`. This mirrors
`k8s/hetzner/app` in a new `k8s/hetzner/app-chocolate` overlay.

**Mutually exclusive, on purpose, not by accident.** `auth-server`'s `spa-client`
OAuth2 client has exactly one `redirectUri`/`postLogoutRedirectUri`, driven by the
single-valued `SPA_CLIENT_REDIRECT_URI` / `SPA_CLIENT_POST_LOGOUT_REDIRECT_URI` env
vars (`SecurityConfig.java`, `spaClientRedirectUri`). Same for
`AUTH_SERVER_ISSUER` — the JWT `iss` claim is one URL. So the backend can only be
configured to trust redirects to *one* of `granite-security.org` or
`sichocolate.com` at a time. `app` and `app-chocolate` must never both be applied
to the cluster simultaneously — applying one is how you switch away from the
other. This doc treats that as the intended model, not a limitation to design
around.

## 0. What's shared vs. what's new

| Shared (unchanged) | New for sichocolate.com |
|---|---|
| Namespace `granite`, all backend Deployments/Services from `k8s/base` | `ui-demo` Docker image + K8s manifest |
| `granite-gateway` / `granite-route` object *names* (reused, just retargeted) | `sichocolate.com` DNS record + TLS cert |
| `letsencrypt-prod` ClusterIssuer (already parents to `granite-gateway` by name) | `app-chocolate/` kustomize overlay |
| Traefik, cert-manager, CoreDNS install (`cloudify.md` §4) | A `sichocolate.override` CoreDNS host entry |

Because the Gateway/HTTPRoute keep the **same object names** as `app/`, switching
overlays is an in-place `kubectl apply -k`, not a delete-then-recreate — no orphaned
Gateway API objects to clean up.

## 1. Dockerize ui-demo

`ui-demo/` currently has no Dockerfile — it's only ever been run via `npm run dev`.
Give it the same packaging `ui-shop/` already has, since both are served the same
way (nginx + runtime env substitution + reverse proxy to the gateway):

1. Copy these files from `ui-shop/` into `ui-demo/`, as-is:
   - `nginx-proto-map.conf`
   - `nginx.conf` (proxies `/api/`, `/auth/`, `/oauth2/` to `http://gateway:8080`,
     serves the SPA with `try_files $uri $uri/ /index.html`)
   - `nginx-ssl.conf.disabled`
   - `docker-entrypoint.sh` (envsubst-renders `config.template.js` → `config.js`;
     only enables the `:443` block if a real cert is mounted)
   - `Dockerfile`

2. `ui-demo/public/config.js` already exists (dev fallback) but there's no
   `config.template.js` yet — add one, copied from `ui-shop/public/config.template.js`,
   substituting the same three vars: `OIDC_AUTHORITY`, `OIDC_CLIENT_ID`,
   `STRIPE_PUBLISHABLE_KEY`. Same vars, because it's the same auth/payment backend —
   only the UI skin differs.

3. Sanity-build locally:
   ```
   cd ui-demo
   docker build -t granite-ui-demo:latest .
   ```

4. Push it under the same Docker Hub org the other images use
   (`docker.io/moldovean/granite-ui-demo`), same tagging convention as
   `app/kustomization.yaml` (`newTag` bumped to the git short SHA on every release —
   see the comment there for why floating `:latest` + `imagePullPolicy: Always` is
   the deliberate choice for this single-node cluster).

## 2. Add a ui-demo K8s manifest

Add `k8s/base/ui-demo/ui-demo.yaml`, copied from `k8s/base/ui-shop.yaml` with
`ui-shop` → `ui-demo` throughout (Deployment name, container name, image, Service
name, `app: ui-demo` label/selector). Keep the same env wiring
(`OIDC_AUTHORITY`/`OIDC_CLIENT_ID` from `granite-config`, `STRIPE_PUBLISHABLE_KEY`
from `granite-secrets`) and the same TLS-cert volume (used only by `kind`, dropped
in production the same way `ui-shop-patch.yaml` drops it).

It lives in its own `k8s/base/ui-demo/` mini-kustomization (a `kustomization.yaml`
that just lists `ui-demo.yaml` as its sole resource), not as a bare file directly
under `k8s/base/`. This isn't cosmetic: kustomize's default load restriction only
allows a bare-file resource to be referenced from *within* the referencing
kustomization's own directory tree; a nested kustomization directory is exempt and
can be pulled in by relative path from anywhere, the same way `../../../k8s/base`
itself already is. Since `app-chocolate` needs to add this resource without going
through `k8s/base/kustomization.yaml`, it has to be a directory, not a file.

**Do not** add it to `k8s/base/kustomization.yaml`'s `resources:` list. Both
`k8s/kind` and `k8s/hetzner/app` build directly on `k8s/base` and don't need a
second idle front-end pod — only `app-chocolate` references
`../../../k8s/base/ui-demo` directly.

## 3. `k8s/hetzner/app-chocolate/`

Create this folder alongside `k8s/hetzner/app/`, with the following files.

### `kustomization.yaml`

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: granite

resources:
  - ../../../k8s/base
  - ../../../k8s/base/ui-demo
  - gateway.yaml

patches:
  - path: config-patch.yaml
  - path: ui-demo-patch.yaml
  - path: remove-ui-shop.yaml
  - path: production-patches.yaml
  - path: secrets-patch.yaml

images:
  - name: granite-auth-server
    newName: docker.io/moldovean/granite-auth-server
    newTag: latest
  - name: granite-gateway
    newName: docker.io/moldovean/granite-gateway
    newTag: latest
  - name: granite-greetings
    newName: docker.io/moldovean/granite-greetings
    newTag: latest
  - name: granite-shop
    newName: docker.io/moldovean/granite-shop
    newTag: latest
  - name: granite-payment
    newName: docker.io/moldovean/granite-payment
    newTag: latest
  - name: granite-profile
    newName: docker.io/moldovean/granite-profile
    newTag: latest
  - name: granite-delivery
    newName: docker.io/moldovean/granite-delivery
    newTag: latest
  - name: granite-ui-demo
    newName: docker.io/moldovean/granite-ui-demo
    newTag: latest
```

Same backend image list as `app/kustomization.yaml`, just `granite-ui-demo`
instead of `granite-ui-shop`.

### `gateway.yaml`

Copy of `app/gateway.yaml` with the hostname, cert secret name, and route backend
swapped — everything else (the plain-HTTP listener for ACME, the HTTPS→redirect
route, the `letsencrypt-prod` annotation) is identical:

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata:
  name: granite-gateway
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-prod
spec:
  gatewayClassName: traefik
  listeners:
    - name: https
      hostname: sichocolate.com
      port: 443
      protocol: HTTPS
      allowedRoutes:
        namespaces:
          from: Same
      tls:
        mode: Terminate
        certificateRefs:
          - name: sichocolate.com-tls
    - name: http
      hostname: sichocolate.com
      port: 80
      protocol: HTTP
      allowedRoutes:
        namespaces:
          from: Same
---
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: granite-http-redirect
spec:
  parentRefs:
    - name: granite-gateway
      sectionName: http
  hostnames:
    - sichocolate.com
  rules:
    - filters:
        - type: RequestRedirect
          requestRedirect:
            scheme: https
            statusCode: 301
---
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: granite-route
spec:
  parentRefs:
    - name: granite-gateway
      sectionName: https
  hostnames:
    - sichocolate.com
  rules:
    - matches:
        - path:
            type: PathPrefix
            value: /
      backendRefs:
        - name: ui-demo
          port: 80
```

Object names (`granite-gateway`, `granite-http-redirect`, `granite-route`) are kept
identical to `app/gateway.yaml` on purpose — see §0. The `letsencrypt-prod`
ClusterIssuer's `gatewayHTTPRoute` solver already has `parentRefs` pointing at
`granite-gateway` in namespace `granite` by name (`platform/cluster-issuer.yaml`),
so no ClusterIssuer change is needed — it'll solve the challenge for whichever
hostname is currently on that Gateway.

### `config-patch.yaml`

Same keys as `app/config-patch.yaml`, values pointed at the new domain:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: granite-config
data:
  AUTH_SERVER_ISSUER: "https://sichocolate.com/auth"
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: "https://sichocolate.com/auth"
  SPA_CLIENT_REDIRECT_URI: "https://sichocolate.com/callback"
  SPA_CLIENT_POST_LOGOUT_REDIRECT_URI: "https://sichocolate.com"
  OIDC_CLIENT_REDIRECT_URI: "https://sichocolate.com/login/oauth2/code/oidc-client"
  OIDC_CLIENT_POST_LOGOUT_REDIRECT_URI: "https://sichocolate.com/"
  OIDC_AUTHORITY: "https://sichocolate.com/auth"
  OIDC_CLIENT_ID: "spa-client"
```

### `ui-demo-patch.yaml`

Same as `app/ui-shop-patch.yaml`, `ui-shop` → `ui-demo`: drops the `kind`-only
TLS volume/mount/443 port and converts the Service to `ClusterIP` (Traefik
terminates TLS in production, not nginx itself).

### `remove-ui-shop.yaml`

New — not present in `app/`. Since `k8s/base` always creates `ui-shop`, and this
overlay is mutually exclusive with `app/` (§0), delete it here so a stray
unused pod doesn't run:

```yaml
$patch: delete
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ui-shop
---
$patch: delete
apiVersion: v1
kind: Service
metadata:
  name: ui-shop
```

### `production-patches.yaml`

Copy `app/production-patches.yaml` verbatim, but rename the `ui-shop` Deployment
block to `ui-demo` (container name too) and drop the block this overlay's
`remove-ui-shop.yaml` already deletes. Everything else (JVM services, Kafka,
Postgres resource limits) is identical — same backend, same sizing.

### `secrets-patch.yaml`

Gitignored, same as `app/secrets-patch.yaml`. Copy `app/secrets-patch.yaml.example`
→ `app-chocolate/secrets-patch.yaml.example` unchanged (same secret keys, same
backend), then have whoever deploys this overlay fill in real values the same way
as `cloudify.md` describes for `app/`.

## 4. DNS

Add a Cloudflare `A` record for `sichocolate.com` → `88.99.149.31` (same VPS),
**DNS-only / grey-cloud**, for the same reason as `cloudify.md` §0/§3: cert-manager's
HTTP-01 solver needs to reach the node directly, which an orange-cloud proxy record
would break.

## 5. CoreDNS split-horizon entry

`platform/coredns-custom.yaml`'s `coredns-custom` ConfigMap can hold more than one
override file as separate `data` keys (CoreDNS's Corefile does
`import /etc/coredns/custom/*.override`, picking up all of them). Add a second key
rather than replacing the existing one — both domains' in-cluster resolution can
coexist even though only one overlay's pods are live at a time:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: coredns-custom
  namespace: kube-system
data:
  granite-security.override: |
    hosts {
      10.233.35.75 granite-security.org
      fallthrough
    }
  sichocolate.override: |
    hosts {
      10.233.35.75 sichocolate.com
      fallthrough
    }
```

(Use the real Traefik ClusterIP, same as `cloudify.md`'s `$INGRESS_IP` — it won't
change between overlays since Traefik itself isn't being redeployed.)

```
kubectl apply -f k8s/hetzner/platform/coredns-custom.yaml
kubectl rollout restart deployment coredns -n kube-system
```

## 6. Google OAuth + Stripe

- Google Cloud Console: add
  `https://sichocolate.com/login/oauth2/code/google` as an **additional**
  authorized redirect URI on the existing OAuth client (don't remove the
  `granite-security.org` one — no harm leaving both registered even though only
  one is reachable at a time).
- Stripe: the publishable key is fine as-is (same Stripe account). If a webhook
  endpoint is registered by URL (`cloudify.md`'s `secrets-patch.yaml.example`
  comment), add `https://sichocolate.com/api/secured/payment/webhook` as an
  additional endpoint so it keeps firing regardless of which overlay is currently
  live.

## 7. Switching between the two skins

**`kubectl apply -k` does NOT prune.** The `remove-ui-shop.yaml` `$patch: delete`
only removes `ui-shop` from this overlay's *generated* manifest — it does not
touch whatever's already live in the cluster, since plain `apply` only
creates/updates resources present in its own output and never deletes ones that
are merely absent from it (that needs `--prune` with an applyset/label selector,
which this repo doesn't set up). So switching overlays is `apply` **plus** a
manual delete of the other skin's Deployment/Service, every time:

```
# From ui-shop/granite-security.org to ui-demo/sichocolate.com:
kubectl apply -k k8s/hetzner/app-chocolate
kubectl -n granite delete deployment ui-shop
kubectl -n granite delete service ui-shop

# Back the other way:
kubectl apply -k k8s/hetzner/app
kubectl -n granite delete deployment ui-demo
kubectl -n granite delete service ui-demo
```

**`apply` also does not restart pods on a ConfigMap change.** Every service reads
its issuer/redirect config via `configMapKeyRef` at container start, not as a
live-reloaded value — and it's not just `auth-server`/`gateway`.
`greetings`/`shop`/`payment`/`profile`/`delivery` are *each independently* an
OAuth2 resource server that fetches
`https://<domain>/auth/.well-known/openid-configuration` at startup using its own
`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`. Miss restarting even one
and it keeps validating JWTs against the old domain — which, once that domain's
Gateway listener is gone, fails PKIX validation against Traefik's fallback default
cert and surfaces as a 500 on every authenticated request to that service (hit
this for real: `profile`'s `/api/profiles/me/addresses` 500'd with
`SSLHandshakeException: PKIX path building failed` because only
`auth-server`/`gateway` had been restarted). Restart all seven config-consuming
deployments after every switch:

```
kubectl -n granite rollout restart deployment auth-server gateway greetings shop payment profile delivery
kubectl -n granite rollout status deployment auth-server --timeout=120s
kubectl -n granite rollout status deployment gateway --timeout=120s
kubectl -n granite rollout status deployment greetings --timeout=120s
kubectl -n granite rollout status deployment shop --timeout=120s
kubectl -n granite rollout status deployment payment --timeout=120s
kubectl -n granite rollout status deployment profile --timeout=120s
kubectl -n granite rollout status deployment delivery --timeout=120s
```

cert-manager requests a fresh certificate for whichever hostname is now on the
Gateway automatically — same mechanism described in `cloudify.md` §11, no manual
step needed there. In practice this issued within ~90s on the first real cutover.

**The domain being switched away from doesn't get a clean shutdown** — its Gateway
listener hostname is simply gone once the other overlay is applied, so requests to
it fall through to Traefik's own default self-signed cert (`CN=TRAEFIK DEFAULT
CERT`) and a 404, rather than anything meaningful. This is harmless (nothing
sensitive leaks — it's Traefik's stock cert) but looks alarming if you hit it by
accident; expect it, don't treat it as a break.

Verify after switching:
```
kubectl -n granite get certificate
kubectl -n granite get pods                  # confirm the old skin's pod is gone, new one is Running
curl -sI https://sichocolate.com/            # 200 from ui-demo through the Gateway + TLS
curl -s https://sichocolate.com/api/greetings | jq .
```
