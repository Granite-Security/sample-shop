# TODO — Run the full granite-security stack in local `kind`, without breaking IntelliJ-local dev

## 0. Scope (updated per your answers below)

**In scope now — all app services:** `auth-server`, `gateway`, `ui-shop`, `shop`, `payment`,
`delivery`, `profile`, plus `kafka` and all 5 Postgres DBs. Manifests for every one of these
already exist in `k8s/base/` and already follow the same env-var pattern (verified: `payment`,
`profile`, `delivery` application.yaml all mirror `shop`'s — `SERVER_PORT`, `*_R2DBC_URL`,
`*_JDBC_URL`, `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`, `KAFKA_BOOTSTRAP_SERVERS`
all env-var-driven with `localhost` defaults — no code changes needed for any of them).

**Still deferred (pure tooling, nothing depends on them):** `schema-registry`, `kafka-ui`. Nothing
in the app reads from schema-registry (no Avro/Confluent serializers in use) and kafka-ui is a
UI-only debugging aid. Skip both unless you want Kafka topic visibility while testing.

**Run mode:** exclusive only — kind and IntelliJ-local never run at the same time. Confirmed, so
§5 of the previous draft (hybrid host-networking recipe) is dropped entirely — not needed.

**Cluster:** reuse the `granite` kind cluster name + `granite` namespace. You've already deleted
the old (dead) `granite` cluster registration, so the next `kind create cluster` is a clean start.

---

## 1. The important part: making SPA login actually work in kind

This is the piece that failed last time, so it gets its own section before any deploy steps.

**How login is supposed to work:** `auth-server` is the OIDC Authorization Server. `ui-shop` is
registered as `spa-client` — a **public** client using **PKCE** (`ClientSettings.requireProofKey
(true)`, no client secret, `oidc-client-ts`'s `UserManager` in `ui-shop/src/oauth.ts`). The
browser drives the whole code+PKCE exchange directly against `auth-server` (via the gateway
proxy) — the gateway and its own OAuth2-client/token-relay machinery (`oidc-client` /
`GateSec`) is a *separate*, older flow used for server-relayed calls and is **not** involved in
the SPA's login. For the SPA flow, the JWT's `iss` claim (set by `auth-server` from
`AUTH_SERVER_ISSUER` / `spring.security.oauth2.authorizationserver.issuer` — env var, already
correct) must **exactly match** whatever issuer string `oidc-client-ts` in the browser is told to
expect. That's the one identity that has to line up across environments.

### Bug found: `ui-shop` hardcodes the issuer, and the runtime-config mechanism meant to fix that is dead code

- `ui-shop/src/oauth.ts` hardcodes, as string literals:
  ```ts
  authority: 'http://localhost:8080/auth',
  ...
  metadata: { issuer: 'http://localhost:8080/auth', ... }
  ```
- There's already a **runtime-config mechanism built for exactly this** —
  `ui-shop/public/config.template.js` gets rendered to `/config.js` by
  `ui-shop/docker-entrypoint.sh` via `envsubst`, injecting `OIDC_AUTHORITY` / `OIDC_CLIENT_ID` /
  `STRIPE_PUBLISHABLE_KEY` from the container's env at startup (so the same built image works in
  any environment). This is exactly the "prefer env vars" pattern we want.
- **But it's never actually wired up:** `ui-shop/index.html` does not load `/config.js` in a
  `<script>` tag at all, and `oauth.ts` never reads `window.__ENV__`. The whole mechanism is
  orphaned — built, but disconnected.
- **Net effect in kind:** `auth-server` will issue tokens with `iss = http://gateway:8080/auth`
  (per `AUTH_SERVER_ISSUER` in `config-patch.yaml`), but the SPA will always validate against the
  hardcoded `http://localhost:8080/auth`. That's a hard mismatch — `oidc-client-ts` will reject
  the sign-in response as soon as it checks the issuer. **This is almost certainly the exact
  failure you hit last time.**
- Related, same family of bug, not blocking login: `ui-shop/src/pages/Checkout.tsx` reads the
  Stripe key via `import.meta.env.VITE_STRIPE_PUBLISHABLE_KEY` — a Vite **build-time** env var,
  baked into the JS bundle at `npm run build`. It can't be swapped at container start the way
  `OIDC_AUTHORITY` is meant to be. Not a login blocker; worth a look once you're testing
  checkout in kind, since the same prebuilt `granite-ui-shop:latest` image can't carry two
  different Stripe keys for two environments as-is.

### Fix applied (4 files — the 3 proposed + the ambient type declaration)

1. **`ui-shop/index.html`** — add `<script src="/config.js"></script>` immediately before
   `<script type="module" src="/src/main.tsx"></script>`. Classic (non-module) scripts execute
   before deferred module scripts, so `window.__ENV__` is guaranteed to exist before `oauth.ts`
   runs — no race condition.
2. **`ui-shop/public/config.js`** — uncomment the `window.__ENV__ = {...}` block (it's currently
   entirely commented out) with today's values as defaults
   (`OIDC_AUTHORITY: "http://localhost:8080/auth"`, `OIDC_CLIENT_ID: "spa-client"`,
   `STRIPE_PUBLISHABLE_KEY: ""`), so plain `npm run dev` behaves exactly as it does today.
3. **`ui-shop/src/oauth.ts`** — replace the two hardcoded `'http://localhost:8080/auth'` literals
   with `window.__ENV__?.OIDC_AUTHORITY ?? 'http://localhost:8080/auth'` (and `OIDC_CLIENT_ID`
   the same way), used for **both** `authority` and `metadata.issuer`. Leave the
   `authorization_endpoint` / `token_endpoint` / `jwks_uri` / `userinfo_endpoint` /
   `end_session_endpoint` values exactly as they are today — built from `window.location.origin`
   — since those are always reached through a same-origin reverse proxy (Vite's dev proxy
   locally, `ui-shop`'s nginx in kind) and don't need to change; only the issuer identity does.
   - Needs a one-line ambient type declaration for `window.__ENV__` (e.g. add to
     `ui-shop/src/vite-env.d.ts`) to keep TypeScript happy — the only "extra" beyond the 3 files.

This is a config-plumbing fix, not new behavior — it makes the already-authored runtime-config
mechanism actually take effect, and changes nothing about default (no-env-var) behavior.

- [x] Applied: `ui-shop/index.html` loads `/config.js`; `ui-shop/public/config.js` dev-fallback
      uncommented; `ui-shop/src/oauth.ts` reads `window.__ENV__?.OIDC_AUTHORITY` /
      `OIDC_CLIENT_ID` for both `authority` and `metadata.issuer`, falling back to today's
      defaults; added `ui-shop/src/vite-env.d.ts` for the `window.__ENV__` ambient type.
      `tsc -b --noEmit` shows no new errors from this change (2 pre-existing, unrelated errors in
      `Checkout.tsx`/`Profile.tsx` remain, present on `HEAD` before this edit too).
- [x] Those 2 pre-existing errors turned out to actually block `docker build` (`npm run build`
      runs `tsc -b`, unlike `npm run dev`, which never type-checks) — fixed both since they were
      blocking, not just cosmetic:
      - `Checkout.tsx`: `elementsOptions` `useMemo` returned `null` in the not-ready case, but
        `<Elements options={...}>` is typed `StripeElementsOptions | undefined` (doesn't accept
        `null`). Changed the fallback from `null` to `undefined`.
      - `Profile.tsx`: destructured `user` from `useAuth()` but never used it anywhere in the
        file (`noUnusedLocals` catches this) — removed it from the destructure.
      Verified: `npm run build` and `docker build -t granite-ui-shop:latest ui-shop/` both succeed
      cleanly now.
- [ ] Still to do: actually exercise the login flow in kind (§5) to confirm the fix works
      end-to-end, not just that it type-checks/builds.

---

## 2. Other dead/unused config found (cleanup, not blocking anything)

- `APP_LOGIN_PAGE_URL` (a ConfigMap key, overridden per-overlay in `config-patch.yaml`) is never
  read by any Java code. The actual login redirect in `auth-server/.../SecurityConfig.java` uses
  `issuer + "/login"`, built directly from the `issuer` value itself — which is already correct.
  Functionally harmless (the right thing happens anyway), just misleading dead configuration.
- [x] Removed `APP_LOGIN_PAGE_URL` from `k8s/base/config.yaml`, `k8s/kind/config-patch.yaml`, and
      the env entry in `k8s/base/auth-server.yaml`. Verified with `kubectl kustomize k8s/kind`
      (renders cleanly, 37 resources, zero remaining references to the key).

---

## 3. `/etc/hosts` — what you need to add

Just one entry, matching the front-door design already documented in `kind/kind.md`:

```
127.0.0.1 gateway
```

Nothing else. In this design `ui-shop`'s nginx (exposed on the kind NodePort mapped to host
`:8080`) is the **only** thing the browser talks to directly; it reverse-proxies `/api/`, `/auth/`,
`/oauth2/` to the `gateway` Service inside the cluster. `greetings`/`shop`/`payment`/etc. are never
addressed directly by the browser, so they need no host entries. (Why `gateway` specifically and
not e.g. `granite.localhost`: any `*.localhost` hostname is special-cased straight to 127.0.0.1 by
curl/browsers/musl per RFC 6761, which would bypass DNS entirely — pods inside the cluster could
never resolve it to the real gateway Service. `gateway` has no such special-casing, so it resolves
correctly on both sides: `/etc/hosts` → 127.0.0.1 for the browser, K8s DNS → the Service for pods.)

The kind cluster's port mapping (`k8s/kind/kind-config.yaml`) forwards host `:8080` →
NodePort `30080` (ui-shop). There's a second, currently **dead** mapping, host `:9090` → NodePort
`30090` — see §4.

---

## 4. Gaps found in the existing manifests (fix or consciously accept before deploying)

- [ ] **Dead port mapping:** `k8s/kind/kind-config.yaml` maps host `9090` → NodePort `30090`, but
      no Service anywhere exposes NodePort `30090` (`auth-server`'s Service is ClusterIP-only).
      The browser never needs direct access to `auth-server` (only through the gateway proxy via
      `ui-shop`'s nginx), so recommend just **deleting this unused mapping** from
      `kind-config.yaml` to avoid confusion — no functional loss.
- [ ] **`k8s/base/secrets.yaml`** is git-ignored (correctly — it already holds real Stripe test
      keys + a real Google OAuth client secret locally). It won't come from a fresh clone. Add a
      `k8s/base/secrets.yaml.example` with blank placeholders for the next machine/clone. (Plain
      new file, not a code change.)
- [ ] Confirm `AUTH_SERVER_ISSUER` / `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` /
      `OIDC_AUTHORITY` stay byte-identical across `k8s/base/config.yaml` and
      `k8s/kind/config-patch.yaml` — checked once already (they do, both say
      `http://gateway:8080/auth`), re-check after any edits. This triple staying identical is the
      single most important invariant in the whole setup — a mismatch here (or between here and
      the `oauth.ts` fix in §1) is the "Issuer did not match" class of failure.
- [ ] Confirm the `oidc-client`/`spa-client` `redirect-uri`/`post-logout-redirect-uri` values
      registered in `SecurityConfig.java` (read from `OIDC_CLIENT_REDIRECT_URI` /
      `SPA_CLIENT_REDIRECT_URI` env vars — no hardcoding there, good) match what
      `config-patch.yaml` sets for kind (`http://gateway:8080/...`) — already checked, matches.

---

## 5. Build, load, deploy, verify (exclusive-mode path, full stack)

- [ ] `kind create cluster --config k8s/kind/kind-config.yaml --name granite`
- [ ] `echo '127.0.0.1 gateway' | sudo tee -a /etc/hosts` (if not already present)
- [ ] Build jars (skip tests for speed):
  ```bash
  for s in auth-server gateway greetings shop payment profile delivery; do
    (cd $s && ./gradlew build -x test)
  done
  ```
- [ ] Build images:
  ```bash
  for s in auth-server gateway greetings shop payment profile delivery; do
    docker build -t granite-$s:latest $s/
  done
  docker build -t granite-ui-shop:latest ui-shop/
  ```
- [ ] Load into kind:
  ```bash
  kind load docker-image --name granite \
    granite-auth-server:latest granite-gateway:latest granite-greetings:latest \
    granite-shop:latest granite-payment:latest granite-profile:latest \
    granite-delivery:latest granite-ui-shop:latest
  ```
- [ ] Fill in real values in `k8s/base/secrets.yaml` (Stripe test keys, Google OAuth
      credentials — already present locally per your earlier setup).
- [ ] `kubectl apply -k k8s/kind`
- [ ] Watch rollout: `kubectl -n granite get pods -w` — expect every Deployment (`auth-server`,
      `gateway`, `greetings`, `shop`, `payment`, `profile`, `delivery`, `ui-shop`, 5×`postgres-*`,
      `kafka`) to reach `Running`/`Ready`. The `wait-for-issuer` initContainers on
      `greetings`/`shop`/`payment`/`profile`/`delivery` will block those pods until
      `auth-server`+`gateway` are up — expect a `Init:0/1` wait, not a crash.
- [ ] Functional checks:
  - `curl -s http://localhost:8080/api/shop/products` → public catalog read through gateway → shop.
  - **The one that matters most:** load `http://gateway:8080/` in a browser → SPA loads → click
    login → PKCE redirect to `http://gateway:8080/auth/login` → form login `user`/`user` →
    redirected back to the SPA **authenticated** (no console errors about issuer/state mismatch).
  - Once logged in: browse catalog, place a test order, confirm the order → Kafka → payment →
    Kafka → order-PAID round trip completes (payment sync endpoint if no Stripe webhook listener
    is running locally against kind).
- [ ] **Teardown + local-mode acceptance test:** `kind delete cluster --name granite`, then start
      `auth-server`/`gateway`/`shop`/`payment`/`profile`/`delivery`/`ui-shop` from IntelliJ exactly
      as before, **no env vars set**, confirm the whole flow still works unchanged at `localhost`
      ports. This is the real acceptance test for "doesn't break local dev."

---

## 6. Reference — files this touches

| File | Change |
|---|---|
| `ui-shop/index.html` | +1 line: load `/config.js` (pending your go-ahead, §1) |
| `ui-shop/public/config.js` | uncomment dev-fallback block (pending your go-ahead, §1) |
| `ui-shop/src/oauth.ts` | read `window.__ENV__` instead of hardcoded issuer (pending your go-ahead, §1) |
| `ui-shop/src/vite-env.d.ts` | add `window.__ENV__` ambient type (pending your go-ahead, §1) |
| `k8s/kind/kind-config.yaml` | drop dead `9090→30090` port mapping (§4) |
| `k8s/base/secrets.yaml.example` | new file, blank placeholders (§4) |
| `k8s/base/config.yaml` / `k8s/kind/config-patch.yaml` / `auth-server.yaml` | optional: drop unused `APP_LOGIN_PAGE_URL` (§2) |

No other application code needs to change — every service's peer URLs, issuer, and DB/Kafka
connection strings are already env-var-driven with `localhost` defaults.
