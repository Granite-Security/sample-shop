# k8s Deployment Fixes — Proposal

This document proposes the concrete corrections needed to make the Granite Security
stack run on a local **kind** cluster with a working browser login and JWT-validated
API flow.

It reflects the following decisions:

- **#3 SPA front door → Option A** (ui-shop nginx is the front door; gateway stays internal).
- **#4 ui-shop config → runtime config** (no build-time baking of `VITE_*` / OIDC authority).
- **Startup ordering → keep it**: resource servers require the auth-server to be online
  before they start, so we enforce **auth-server first**.

---

## The one invariant that governs everything

Spring resource servers (`greetings`, `shop`, `payment`, `profile`, `delivery`) validate
every JWT by fetching `{issuer}/.well-known/openid-configuration` + JWKS at startup, and
by comparing the token's `iss` claim against their configured `issuer-uri`.

The auth-server stamps exactly **one** `iss` value into every token. Therefore the issuer
URL must be **one single string that is byte-for-byte identical AND reachable from all three
sides**:

1. the browser SPA (`oidc-client-ts` rejects any ID token whose `iss` ≠ its configured `authority`),
2. the resource-server pods (discovery + JWKS fetch, `iss` check),
3. the auth-server itself (`AUTH_SERVER_ISSUER`).

Everything below exists to preserve this invariant.

---

## Correction 1 — Unify the issuer to one shared, dual-resolvable hostname (CRITICAL)

### The bug

`k8s/kind/config-patch.yaml` currently sets:

```yaml
AUTH_SERVER_ISSUER: "http://gateway.granite.svc.cluster.local:8080/auth"
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: "http://gateway.granite.svc.cluster.local:8080/auth"
APP_LOGIN_PAGE_URL: "http://localhost:8080/auth/login"
```

- Tokens are stamped with `iss = http://gateway.granite.svc.cluster.local:8080/auth`.
- The SPA (`ui-shop/src/oauth.ts`) is hard-coded to `authority = http://localhost:8080/auth`
  → `oidc-client-ts` rejects the token (issuer mismatch).
- `gateway.granite.svc.cluster.local` is **not resolvable from the browser** at all, so even
  the redirects can't work externally.

### The fix

Pick a single hostname that resolves to the front door **from the browser** and to the
auth path **from inside the cluster**. Recommended: **`granite.localhost`** (mirrors the
compose approach that uses `gateway` as a shared host).

Rewrite `k8s/kind/config-patch.yaml` so all three values agree:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: granite-config
data:
  AUTH_SERVER_ISSUER: "http://granite.localhost:8080/auth"
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: "http://granite.localhost:8080/auth"
  APP_LOGIN_PAGE_URL: "http://granite.localhost:8080/auth/login"
```

Make `granite.localhost` resolve on both sides:

- **Browser:** add `127.0.0.1 granite.localhost` to `/etc/hosts` (same idea as the compose
  `README` step that adds `127.0.0.1 auth greetings gateway`). The kind NodePort maps host
  `8080` → the ui-shop front door (see Correction 3).
- **Pods:** add a CoreDNS rewrite so the in-cluster resource servers resolve the same host
  to the gateway (which serves `/auth`):

  ```
  rewrite name granite.localhost gateway.granite.svc.cluster.local
  ```

  Add this to the CoreDNS `Corefile` ConfigMap (`kubectl -n kube-system edit configmap coredns`)
  or ship it as an overlay patch. Pods then fetch discovery/JWKS from the gateway directly,
  while the browser reaches the identical path through the ui-shop nginx proxy.

Result: the `iss` string is `http://granite.localhost:8080/auth` everywhere — identical for
the browser, the pods, and the auth-server.

> The `k8s/base/config.yaml` defaults already use `http://gateway:8080/auth`. Keep the base
> as-is; the kind overlay above is what the local cluster actually uses. Just ensure the
> overlay no longer diverges to cluster-DNS names.

---

## Correction 2 — Inject the SPA client redirect URI (login 400 without this)

`auth-server/application.yaml` registers the SPA client redirect as
`${SPA_CLIENT_REDIRECT_URI:http://localhost:5173/callback}`, but `k8s/base/auth-server.yaml`
never injects `SPA_CLIENT_REDIRECT_URI`. The SPA sends `redirect_uri = origin + '/callback'`,
so the auth-server rejects it as an unregistered redirect.

Add to `k8s/base/config.yaml` (and align the existing OIDC redirect keys with the new host):

```yaml
SPA_CLIENT_REDIRECT_URI: "http://granite.localhost:8080/callback"
OIDC_CLIENT_REDIRECT_URI: "http://granite.localhost:8080/login/oauth2/code/oidc-client"
OIDC_CLIENT_POST_LOGOUT_REDIRECT_URI: "http://granite.localhost:8080/"
```

Inject `SPA_CLIENT_REDIRECT_URI` in `k8s/base/auth-server.yaml` env, following the existing
`configMapKeyRef` pattern:

```yaml
            - name: SPA_CLIENT_REDIRECT_URI
              valueFrom:
                configMapKeyRef:
                  name: granite-config
                  key: SPA_CLIENT_REDIRECT_URI
```

---

## Correction 3 — SPA front door via ui-shop (Option A, chosen)

### The bug

`RouterConfig.indexRedirect()` only handles `GET /` and 302-redirects the browser to
`spaOrigin` = `MICROSERVICES_SPA_URI` = `http://ui-shop:80`, which the browser cannot resolve.
The gateway does not proxy SPA assets or client-side routes (`/callback`, `/assets/**`, `/cart`, …).

### The fix (Option A — ui-shop is the front door)

`ui-shop/nginx.conf` already serves the SPA from `/usr/share/nginx/html` and proxies `/api`,
`/auth`, and `/oauth2` to `gateway:8080`. So ui-shop is the natural front door.

1. **Move the NodePort from gateway to ui-shop.** Delete `k8s/kind/gateway-patch.yaml`
   (gateway stays `ClusterIP`), and add a `ui-shop` NodePort patch:

   ```yaml
   # k8s/kind/ui-shop-patch.yaml
   apiVersion: v1
   kind: Service
   metadata:
     name: ui-shop
   spec:
     type: NodePort
     ports:
       - port: 80
         targetPort: 80
         nodePort: 30080
   ```

2. **Update `k8s/kind/kustomization.yaml`** to reference the new patch:

   ```yaml
   patches:
     - path: ui-shop-patch.yaml
     - path: config-patch.yaml
   ```

3. **Keep the kind port mapping** (`k8s/kind/kind-config.yaml`) as `containerPort: 30080 →
   hostPort: 8080`, so `http://granite.localhost:8080` lands on the ui-shop nginx.

Browser flow becomes: **browser → ui-shop nginx (serves SPA) → proxies `/auth`, `/api`,
`/oauth2` → gateway → services/auth-server.** No gateway `indexRedirect` or SPA-proxy code
change is needed.

---

## Correction 4 — ui-shop uses runtime config, not build-time baking (chosen)

### The bug

`ui-shop/Dockerfile` runs `npm run build` and serves static files via nginx. Vite inlines
`VITE_*` at **build time**, so the runtime `env:` block in `k8s/base/ui-shop.yaml`
(`VITE_STRIPE_PUBLISHABLE_KEY`) has no effect. The OIDC `authority` hard-coded in
`oauth.ts` has the same problem.

### The fix (runtime config)

Generate a small `config.js` at container start via `envsubst` and read it from
`window.__ENV__` in the SPA.

1. **Add a runtime config template** served from the web root, e.g.
   `ui-shop/public/config.template.js`:

   ```js
   window.__ENV__ = {
     OIDC_AUTHORITY: "${OIDC_AUTHORITY}",
     OIDC_CLIENT_ID: "${OIDC_CLIENT_ID}",
     STRIPE_PUBLISHABLE_KEY: "${STRIPE_PUBLISHABLE_KEY}"
   };
   ```

2. **Add a docker entrypoint** that renders it and then starts nginx:

   ```sh
   #!/bin/sh
   envsubst < /usr/share/nginx/html/config.template.js > /usr/share/nginx/html/config.js
   exec nginx -g 'daemon off;'
   ```

   In the Dockerfile: copy the entrypoint, ensure `envsubst` is available
   (`apk add --no-cache gettext`), and set it as `ENTRYPOINT`.

3. **Reference `config.js` in `index.html`** before the app bundle:

   ```html
   <script src="/config.js"></script>
   ```

4. **Read runtime values in `oauth.ts`** instead of the hard-coded literals:

   ```ts
   const env = (window as any).__ENV__ ?? {};
   const authority = env.OIDC_AUTHORITY ?? "http://localhost:8080/auth";
   const client_id  = env.OIDC_CLIENT_ID ?? "spa-client";
   ```

   And read the Stripe key from `window.__ENV__.STRIPE_PUBLISHABLE_KEY`.

5. **Wire the env vars in `k8s/base/ui-shop.yaml`** (repurpose the current `env:` block):

   ```yaml
           env:
             - name: OIDC_AUTHORITY
               value: "http://granite.localhost:8080/auth"
             - name: OIDC_CLIENT_ID
               value: "spa-client"
             - name: STRIPE_PUBLISHABLE_KEY
               valueFrom:
                 secretKeyRef:
                   name: granite-secrets
                   key: stripe-publishable-key
   ```

   (`OIDC_AUTHORITY` can also be sourced from `granite-config` if we add a key for it, so the
   overlay keeps it in lockstep with the issuer.)

This makes the image environment-agnostic; the OIDC authority and Stripe key are decided at
deploy time, guaranteeing the SPA authority matches the issuer from Correction 1.

---

## Correction 5 — Startup ordering: auth-server first (kept, as requested)

Resource servers eagerly fetch OIDC discovery at startup and will `CrashLoopBackOff` if the
issuer isn't reachable yet. Since the issuer resolves (via CoreDNS) to the **gateway**, and
the gateway proxies `/auth` to the **auth-server**, the effective dependency is:

**auth-server ready → gateway ready → resource servers start.**

Add an `initContainer` to `shop`, `payment`, `profile`, `delivery`, and `greetings` that
blocks until the discovery document is served:

```yaml
      initContainers:
        - name: wait-for-issuer
          image: curlimages/curl:latest
          command:
            - sh
            - -c
            - >
              until curl -sf http://granite.localhost:8080/auth/.well-known/openid-configuration;
              do echo "waiting for auth issuer..."; sleep 3; done
```

Notes:

- This makes the `kubectl wait` ordering in `kind.md` reliable and matches your intent that
  the auth-server must be online first.
- The auth-server's own readiness/liveness probes (`/auth/login` on `:9090`) already gate it,
  so ordering is: auth-server passes probes → gateway route works → initContainers succeed →
  resource servers boot.

---

## Correction 6 — Decouple gateway readiness from the auth-server

`k8s/base/gateway.yaml` probes `GET /auth/login` on `:8080`, which forces the gateway to be
"not ready" until the auth-server is up (and 502s from the proxy leak into the probe result).

Point the gateway probes at a gateway-local endpoint that does not depend on a backend:

- Enable Spring Boot Actuator on the gateway and probe `/actuator/health`, **or**
- probe a lightweight local route that the gateway answers itself.

This lets the gateway report ready independently, while Correction 5 handles the real
ordering dependency.

---

## Lower-priority / consistency items

- **CORS:** `gateway/application.yaml` allows `localhost:3000/5173/8080`. Add the new front-door
  origin `http://granite.localhost:8080` to `allowed-origins`.
- **`kind.md` DB names:** the doc lists `delivery-postgres` / `profile-postgres`, but the real
  Services (and the URLs in `config.yaml`) are `postgres-delivery` / `postgres-profile`. Fix the
  doc table.
- **`kind.md` front door:** update the "Accessing everything" section to reflect Option A —
  the front door is `http://granite.localhost:8080` (ui-shop), not the gateway.
- **Postgres PVC layout:** volumes are mounted directly at `/var/lib/postgresql/data`. On some
  provisioners the `lost+found` dir breaks init. On kind's hostpath it's usually fine; if issues
  arise, set `PGDATA=/var/lib/postgresql/data/pgdata` with a `subPath`.
- **Secrets in git:** `k8s/base/secrets.yaml` contains real-looking Stripe/Google secrets in
  plaintext. Rotate them and keep only placeholders in git; source real values from a proper
  secret store.

---

## Summary of file changes

| File | Change |
|------|--------|
| `k8s/kind/config-patch.yaml` | Issuer + login URL → `http://granite.localhost:8080/auth` (Correction 1) |
| CoreDNS `Corefile` | `rewrite name granite.localhost gateway.granite.svc.cluster.local` (Correction 1) |
| `k8s/base/config.yaml` | Add `SPA_CLIENT_REDIRECT_URI`; align OIDC redirect keys (Correction 2) |
| `k8s/base/auth-server.yaml` | Inject `SPA_CLIENT_REDIRECT_URI` (Correction 2) |
| `k8s/kind/gateway-patch.yaml` | Remove (gateway back to ClusterIP) (Correction 3) |
| `k8s/kind/ui-shop-patch.yaml` | New — NodePort 30080 on ui-shop (Correction 3) |
| `k8s/kind/kustomization.yaml` | Reference `ui-shop-patch.yaml` instead of `gateway-patch.yaml` (Correction 3) |
| `ui-shop/` (Dockerfile, entrypoint, `config.template.js`, `index.html`, `oauth.ts`) | Runtime config wiring (Correction 4) |
| `k8s/base/ui-shop.yaml` | Runtime env vars (`OIDC_AUTHORITY`, `OIDC_CLIENT_ID`, `STRIPE_PUBLISHABLE_KEY`) (Correction 4) |
| resource-server deployments | Add `wait-for-issuer` initContainer (Correction 5) |
| `k8s/base/gateway.yaml` | Probe a gateway-local endpoint (Correction 6) |
| `gateway/application.yaml` | Add `http://granite.localhost:8080` to CORS (lower-priority) |
| `k8s/kind/kind.md` | Fix DB names + front-door section (lower-priority) |

**Host prerequisite:** add `127.0.0.1 granite.localhost` to `/etc/hosts`.
