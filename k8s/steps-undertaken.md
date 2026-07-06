# k8s Deployment — Steps Undertaken

This is the running log of implementing the corrections proposed in
[`k8s/fixes.md`](./fixes.md) on a local **kind** cluster. It records both the
**source/manifest changes** and the **shell commands** used to build, deploy, and
verify the stack.

Decisions carried over from `fixes.md`:

- **#3 SPA front door → Option A** (ui-shop nginx is the front door; gateway is internal).
- **#4 ui-shop config → runtime config** (`window.__ENV__` from `/config.js`, no build-time baking).
- **Startup ordering → auth-server first** (a `wait-for-issuer` initContainer on every resource server).

---

## Part A — Source & manifest changes

### Correction 1 — Unify the issuer to one shared, dual-resolvable host

Chosen host: **`granite.localhost`** → `http://granite.localhost:8080/auth`.

- `k8s/kind/config-patch.yaml`: set `AUTH_SERVER_ISSUER`,
  `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`, `APP_LOGIN_PAGE_URL`,
  `OIDC_CLIENT_REDIRECT_URI`, `OIDC_CLIENT_POST_LOGOUT_REDIRECT_URI`,
  `SPA_CLIENT_REDIRECT_URI`, `OIDC_AUTHORITY`, `OIDC_CLIENT_ID` — all on
  `http://granite.localhost:8080/...`.
- `k8s/kind/coredns-patch.yaml` (new): CoreDNS `rewrite name granite.localhost
  gateway.granite.svc.cluster.local`, so **pods** resolve the shared host to the gateway.
- **Browser** side: `127.0.0.1 granite.localhost` in `/etc/hosts` (see Part B).

The `iss` string is now byte-for-byte identical for the browser, the pods, and the auth-server.

### Correction 2 — Inject the SPA client redirect URI

- `k8s/base/config.yaml`: added `SPA_CLIENT_REDIRECT_URI` default.
- `k8s/base/auth-server.yaml`: injected `SPA_CLIENT_REDIRECT_URI` via `configMapKeyRef`.
- kind overlay points it (and the OIDC redirect keys) at `http://granite.localhost:8080/...`.

### Correction 3 — ui-shop is the front door (Option A)

- `k8s/kind/ui-shop-patch.yaml` (new): `ui-shop` Service → `NodePort` `30080`.
- `k8s/kind/gateway-patch.yaml`: **deleted** (gateway stays `ClusterIP`).
- `k8s/kind/kustomization.yaml`: references `ui-shop-patch.yaml` instead of `gateway-patch.yaml`.
- `k8s/kind/kind-config.yaml`: unchanged — still maps container `30080` → host `8080`.

### Correction 4 — ui-shop runtime config (no build-time baking)

- `ui-shop/public/config.template.js` (new): `window.__ENV__` template with `${...}` placeholders.
- `ui-shop/public/config.js` (new): dev fallback for `npm run dev`.
- `ui-shop/docker-entrypoint.sh` (new): renders `config.js` from the template via `envsubst`, then `exec nginx`.
- `ui-shop/Dockerfile`: `apk add --no-cache gettext`, copy the entrypoint, set `ENTRYPOINT`.
- `ui-shop/index.html`: load `/config.js` before the app bundle.
- `ui-shop/src/oauth.ts`: read `authority`/`client_id` from `window.__ENV__` (issuer = authority).
- `ui-shop/src/pages/Checkout.tsx`: read Stripe key from `window.__ENV__` (dev fallback to `import.meta.env`).
- `k8s/base/config.yaml`: added `OIDC_AUTHORITY`, `OIDC_CLIENT_ID` defaults.
- `k8s/base/ui-shop.yaml`: env now `OIDC_AUTHORITY`, `OIDC_CLIENT_ID` (ConfigMap) + `STRIPE_PUBLISHABLE_KEY` (Secret).

### Correction 5 — Startup ordering (auth-server first)

- Added a `wait-for-issuer` initContainer (curls the discovery doc through the gateway) to:
  `k8s/base/greetings.yaml`, `shop.yaml`, `payment.yaml`, `profile.yaml`, `delivery.yaml`.

### Correction 6 — Decouple gateway readiness from the auth-server

- `k8s/base/gateway.yaml`: readiness/liveness probes now hit the gateway-local `/`
  route (its `indexRedirect` answers `302`, which k8s treats as healthy).

### Lower-priority / consistency

- `gateway/src/main/resources/application.yaml`: added `http://granite.localhost:8080` to CORS `allowed-origins`.
- `k8s/kind/kind.md`: fixed DB names (`postgres-delivery`/`postgres-profile`), the
  issuer values, the front-door section, the login-flow diagram, troubleshooting, the
  file reference, and added the `/etc/hosts` + CoreDNS steps.

### Validation of the manifests

```bash
kubectl kustomize k8s/kind > /tmp/kustomize-out.yaml   # exit 0, no errors
# 12x granite.localhost, single NodePort 30080 (on ui-shop), 5x wait-for-issuer initContainers
```

---

## Part B — Deployment commands
