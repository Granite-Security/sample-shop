# Running both domains simultaneously (granite-security.org + sichocolate.com)

**Status: proposal, not started.**

## 0. What's changing vs. what exists today

Today, `cloud/hetzner/app` (granite-security.org / ui-shop) and
`cloud/hetzner/app-chocolate` (sichocolate.com / ui-demo) are **mutually
exclusive** — see `cloud/hetzner/sichocolate.md` §0. Only one can be applied to
the cluster at a time because:

- `auth-server`'s `spa-client` OAuth2 client is configured from single-valued
  `SPA_CLIENT_REDIRECT_URI` / `SPA_CLIENT_POST_LOGOUT_REDIRECT_URI` env vars.
- `AUTH_SERVER_ISSUER` (the JWT `iss` claim, and the OIDC issuer Spring
  Authorization Server advertises) is one fixed URL.
- Every resource server (`greetings`/`shop`/`payment`/`profile`/`delivery`)
  validates JWTs against exactly one
  `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`.
- The K8s Gateway API `Gateway` object (`gateway.yaml` in each overlay) has one
  hostname per listener, routing to one `ui-*` Service.

This doc plans making both domains **live at the same time**, sharing the one
backend (auth-server, gateway, greetings, shop, payment, profile, delivery,
kafka, postgres), with:

- **Two OAuth2 clients** in auth-server, one per domain (`spa-client-shop`,
  `spa-client-chocolate`), each with its own redirect URIs scoped to its own
  domain — not one client with two redirect URIs registered on it. Keeping them
  separate means a code stolen/leaked in one domain's flow can't be redeemed
  against the other client, and each domain's SPA config
  (`OIDC_CLIENT_ID`) stays self-describing.
- **One `auth-server`/`gateway`/backend fleet**, unchanged in replica count —
  the whole point is the backend is common.
- **Gateway API routing that picks the front end (ui-shop vs. ui-demo) by
  hostname**, both resolved through the same Traefik `Gateway`.

## 1. The hard part: the JWT issuer is host-dependent now

This is the crux of the whole plan — everything else is comparatively
mechanical.

### 1a. auth-server: stop hardcoding the issuer, derive it per request

`SecurityConfig.authorizationServerSettings()` currently does:

```java
return AuthorizationServerSettings.builder().issuer(issuer).build();
```

with `issuer` fixed from `AUTH_SERVER_ISSUER`. Spring Authorization Server
supports this being **left unset**: when `AuthorizationServerSettings.issuer`
is null, the framework computes the issuer from the incoming request
(`scheme://host[:port]` at the authorization-server's context path) instead of
a fixed string. This is Spring Authorization Server's built-in
multi-tenancy-by-request mechanism, and it's exactly what's needed here — one
running instance, issuer varies by which domain the request arrived through.

Changes needed:

- Drop the `.issuer(issuer)` call (or make it conditional — see §5 for why kind
  and local dev still want a fixed issuer).
- Since resolution reads the **request's** scheme/host, and every request
  reaches `auth-server` via `nginx → gateway → auth-server` (not directly from
  the browser), `auth-server` and the Spring Cloud `gateway` module must both
  trust and forward `X-Forwarded-*` headers accurately, and re-derive
  scheme/host from them rather than the raw socket.
  `auth-server/application.yaml` **already** sets
  `server.forward-headers-strategy: framework` — no change needed there.
  `gateway/application.yaml` does **not** set it — add it, since Spring Cloud
  Gateway's default `XForwardedHeadersFilter` on a pure pass-through route
  otherwise leaves the reactive server's own `ServerHttpRequest.getURI()`
  un-rewritten from the incoming socket, and only fixing it on the hop that
  terminates the request (auth-server) isn't enough if anything upstream
  inspects `request.getURI()` before forwarding.
- **`ui-shop`/`ui-demo`'s nginx currently hardcodes `proxy_set_header Host
  localhost:8080;`** (see `ui-shop/nginx.conf`) and relies on
  `X-Forwarded-Host: $http_host` to carry the real hostname. Confirm this
  survives through the Spring Cloud Gateway hop unmodified — Spring Cloud
  Gateway's `TokenRelay`/proxy filters don't strip `X-Forwarded-*` by default,
  but this needs an explicit check once `forward-headers-strategy: framework`
  is turned on, since misconfiguration here would make every issued JWT and
  discovery document report `localhost:8080` as the issuer regardless of which
  domain the user is on.
- Sanity check after this change: hit
  `https://granite-security.org/auth/.well-known/openid-configuration` and
  `https://sichocolate.com/auth/.well-known/openid-configuration` from outside
  the cluster and confirm `"issuer"` differs and matches each domain.

### 1b. Resource servers: accept either issuer, not just one

`payment`/`shop`/`profile`/`greetings`/`delivery` each currently configure:

```yaml
spring.security.oauth2.resourceserver.jwt.issuer-uri: ${SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI:...}
```

Spring's `issuer-uri` autoconfiguration does two things: fetches
`{issuer-uri}/.well-known/openid-configuration` to find the JWKS endpoint, and
installs a `JwtIssuerValidator` that rejects any JWT whose `iss` claim doesn't
exactly equal that one configured value. Both assumptions break once
`auth-server` can legitimately issue tokens with two different `iss` values
from the same key material.

Replace `issuer-uri` with an explicit `jwk-set-uri` (points at the JWKS
endpoint directly, bypassing per-host discovery — the key material is the same
regardless of which domain the token was issued under) plus a custom
`JwtDecoder` bean that validates `iss` against an **allow-list** of trusted
issuers instead of a single string:

```java
@Bean
public ReactiveJwtDecoder jwtDecoder() {
    NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
            .withJwkSetUri(jwkSetUri) // e.g. http://auth-server:9090/auth/oauth2/jwks — internal, not domain-specific
            .build();
    OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(),
            new JwtIssuerValidator(trustedIssuers) // custom: iss ∈ {https://granite-security.org/auth, https://sichocolate.com/auth}
    );
    decoder.setJwtValidator(validator);
    return decoder;
}
```

`JwtIssuerValidator` in Spring Security only supports a single issuer, so this
needs a small custom `OAuth2TokenValidator<Jwt>` (a `Set<String>` membership
check on the `iss` claim) — same handful of lines repeated in all five
services, since there's no shared module in this repo (each service is an
independent Gradle/Maven build). Introduce a new env var,
`TRUSTED_JWT_ISSUERS` (comma-separated), read alongside the existing
`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` (which becomes unused
once this lands — remove it from `application.yaml` defaults once all five
services are migrated, to avoid two issuer knobs existing simultaneously).

Apply this to: `payment`, `shop`, `profile`, `greetings`, `delivery` — all five
currently wire `issuer-uri` the same way.

### 1c. RegisteredClientRepository: two SPA clients, not two redirect URIs on one

```java
RegisteredClient spaClientShop = RegisteredClient.withId(UUID.randomUUID().toString())
        .clientId("spa-client-shop")
        .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
        .redirectUri(spaClientShopRedirectUri)             // https://granite-security.org/callback
        .postLogoutRedirectUri(spaClientShopPostLogoutUri)  // https://granite-security.org
        .scope(OidcScopes.OPENID).scope(OidcScopes.PROFILE).scope(StandardClaimNames.EMAIL)
        .clientSettings(ClientSettings.builder().requireAuthorizationConsent(true).requireProofKey(true).build())
        .build();

RegisteredClient spaClientChocolate = RegisteredClient.withId(UUID.randomUUID().toString())
        .clientId("spa-client-chocolate")
        .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
        .redirectUri(spaClientChocolateRedirectUri)             // https://sichocolate.com/callback
        .postLogoutRedirectUri(spaClientChocolatePostLogoutUri) // https://sichocolate.com
        .scope(OidcScopes.OPENID).scope(OidcScopes.PROFILE).scope(StandardClaimNames.EMAIL)
        .clientSettings(ClientSettings.builder().requireAuthorizationConsent(true).requireProofKey(true).build())
        .build();
```

- `oidc-client` (Postman/manual testing) and `external-service`
  (client-credentials) are domain-agnostic and stay as-is.
- Rename the existing `@Value` fields from `spa-client.*` to
  `spa-client-shop.*` / `spa-client-chocolate.*`, backed by new env vars
  `SPA_CLIENT_SHOP_REDIRECT_URI`, `SPA_CLIENT_SHOP_POST_LOGOUT_REDIRECT_URI`,
  `SPA_CLIENT_CHOCOLATE_REDIRECT_URI`,
  `SPA_CLIENT_CHOCOLATE_POST_LOGOUT_REDIRECT_URI`.
- Register both in the same `InMemoryRegisteredClientRepository(...)` call.

### 1d. Frontend config: each SPA already gets its own `OIDC_CLIENT_ID`

`ui-shop`/`ui-demo`'s `config.template.js` already renders `OIDC_CLIENT_ID`
from the `OIDC_CLIENT_ID` ConfigMap key — this already varies per overlay
today (it's just `"spa-client"` in both currently). Once §1c lands, set:

- `cloud/hetzner/app/config-patch.yaml`: `OIDC_CLIENT_ID: "spa-client-shop"`
- `cloud/hetzner/app-chocolate/config-patch.yaml`: `OIDC_CLIENT_ID:
  "spa-client-chocolate"`

`OIDC_AUTHORITY` also stays per-overlay (`https://granite-security.org/auth`
vs. `https://sichocolate.com/auth`) — each SPA only ever talks to its own
domain's issuer for discovery, login, and token exchange; it never needs to
know the other domain exists.

## 2. Merging the two overlays into one

Since `app` and `app-chocolate` are no longer mutually exclusive, they can't
stay as two overlays that each own the *whole* backend (that would try to
create two `auth-server` Deployments named identically in the same namespace,
or worse, two independently-scaled backend fleets that both think they own the
one Postgres). Restructure to:

- `k8s/base` keeps all shared backend resources exactly as-is (unchanged).
- A single new overlay, e.g. `cloud/hetzner/app-multi/`, replaces `app/` and
  `app-chocolate/` as the thing that gets `kubectl apply -k`'d:
  - `resources:` pulls in `k8s/base`, `k8s/base/ui-demo`, **and** keeps
    `ui-shop` (already in `k8s/base`) — both front ends run simultaneously
    now, so nothing needs deleting (drop `remove-ui-shop.yaml` entirely).
  - `gateway.yaml` becomes a **single** Gateway API `Gateway` object with
    **two HTTPS listeners** (one per hostname) and **two HTTP listeners** (one
    per hostname, for the ACME HTTP-01 solver + redirect), plus **two
    `HTTPRoute`s**, each hostname-scoped to its own backend Service. See §3.
  - `config-patch.yaml` needs *both* domains' values simultaneously — this is
    the part that most clearly can't stay "one ConfigMap key = one domain" (see
    §1 above for the resulting `TRUSTED_JWT_ISSUERS` list and the two
    `SPA_CLIENT_*` pairs; `CORS_ALLOWED_ORIGINS` becomes
    `https://granite-security.org,https://sichocolate.com`, and drop
    `AUTH_SERVER_ISSUER`/`OIDC_AUTHORITY`/`OIDC_CLIENT_ID` as cluster-wide
    single values — see §5 for the per-overlay split those need instead).
  - `production-patches.yaml`, `secrets-patch.yaml(.example)` merge
    trivially — same backend sizing, same secrets, just both `ui-shop` and
    `ui-demo` deployment blocks present (no more "drop the block the other
    overlay's remove-patch deletes").
- Retire `cloud/hetzner/app/` and `cloud/hetzner/app-chocolate/` once
  `app-multi/` is verified working, or keep them around temporarily as
  single-domain rollback overlays during migration (see §6).

## 3. Gateway API: routing two hostnames through one Gateway

Gateway API listeners are matched by SNI for TLS and by `Host` header for
HTTP, and a single `Gateway` can hold multiple listeners with different
`hostname` values — this is exactly the mechanism needed, no wildcard or
regex routing required:

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
    - name: https-shop
      hostname: granite-security.org
      port: 443
      protocol: HTTPS
      tls: { mode: Terminate, certificateRefs: [{ name: granite-security.org-tls }] }
      allowedRoutes: { namespaces: { from: Same } }
    - name: https-chocolate
      hostname: sichocolate.com
      port: 443
      protocol: HTTPS
      tls: { mode: Terminate, certificateRefs: [{ name: sichocolate.com-tls }] }
      allowedRoutes: { namespaces: { from: Same } }
    - name: http-shop
      hostname: granite-security.org
      port: 80
      protocol: HTTP
      allowedRoutes: { namespaces: { from: Same } }
    - name: http-chocolate
      hostname: sichocolate.com
      port: 80
      protocol: HTTP
      allowedRoutes: { namespaces: { from: Same } }
---
# two HTTPRoutes, each pinned to its own listener + hostname + backend Service
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata: { name: granite-route-shop }
spec:
  parentRefs: [{ name: granite-gateway, sectionName: https-shop }]
  hostnames: [granite-security.org]
  rules:
    - matches: [{ path: { type: PathPrefix, value: / } }]
      backendRefs: [{ name: ui-shop, port: 80 }]
---
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata: { name: granite-route-chocolate }
spec:
  parentRefs: [{ name: granite-gateway, sectionName: https-chocolate }]
  hostnames: [sichocolate.com]
  rules:
    - matches: [{ path: { type: PathPrefix, value: / } }]
      backendRefs: [{ name: ui-demo, port: 80 }]
---
# + two http→https RequestRedirect HTTPRoutes, one per hostname/http-* listener
```

Notes:

- **cert-manager**: `cert-manager.io/cluster-issuer` on a `Gateway` requests
  one `Certificate` **per HTTPS listener with a distinct `certificateRefs`
  name**, so this naturally produces two Certificates
  (`granite-security.org-tls`, `sichocolate.com-tls`) off one Gateway object —
  no change to `platform/cluster-issuer.yaml` needed, same as noted in
  `sichocolate.md` §3 for the single-domain case.
- The Spring Cloud `gateway` module itself (the `/api/**`, `/auth/**` Java
  routes in `RouterConfig`) needs **no host-based routing change** — it never
  serves the SPA directly. Both `ui-shop` and `ui-demo`'s nginx proxy
  `/api/`, `/auth/`, `/oauth2/` to the same internal `gateway:8080` Service
  regardless of which public domain the browser is on (see `ui-shop/nginx.conf`
  — this is already domain-agnostic since it targets an internal ClusterIP).
- The one thing in `RouterConfig` that **is** domain-aware:
  `indexRedirect()` (`GET /` → `spaOrigin`, single-valued
  `microservices.spa.uri`). This route only fires if something hits gateway's
  `/` directly (nginx never proxies `/` to gateway — it serves the SPA's own
  `index.html` there), so it's low-stakes, but single-valued `spaOrigin` is now
  wrong for one of the two domains. Fix: redirect to `/` (relative, same
  origin) instead of an absolute `spaOrigin` URL — the browser is already on
  the right domain when it reaches gateway through either nginx.

## 4. CORS

`payment`/`shop`/`profile` (the services the SPA calls with `fetch()` directly,
per `PaymentSec.corsConfigurationSource()`) read a comma-separated
`cors.allowed-origins` — this already supports multiple values with **no code
change needed**, just set:

```
CORS_ALLOWED_ORIGINS: "https://granite-security.org,https://sichocolate.com"
```

in the merged `config-patch.yaml`.

## 5. What stays single-valued (and why that's fine)

`k8s/kind` (local dev) and `docker compose` deliberately keep **one** domain
(`localhost:8080`) — there's no multi-domain scenario in local dev, so:

- Leave `AUTH_SERVER_ISSUER` as an *optional* override in
  `SecurityConfig` (§1a) — if set, use it as a fixed issuer (today's
  behavior, needed for `kind`/compose/local runs where per-request issuer
  resolution isn't being exercised); if unset, fall through to Spring
  Authorization Server's per-request derivation. Production
  (`app-multi/config-patch.yaml`) simply omits `AUTH_SERVER_ISSUER` entirely.
- `k8s/kind`'s own overlay and `docker compose` need no changes at all beyond
  making sure `TRUSTED_JWT_ISSUERS` defaults sensibly (e.g. falls back to the
  single `AUTH_ISSUER_URI` default already in each `application.yaml` when
  unset, so local dev doesn't need a new env var to keep working).

## 6. Rollout sequence

Ordering matters because of the chicken/egg between "auth-server can issue
either issuer" and "resource servers accept either issuer" — a mid-rollout
window where a resource server pod is still running the old single-issuer
image will 500 on tokens from the domain it doesn't recognize:

1. **Code first, deploy nowhere yet**: land §1a–§1d and §3's Java/nginx changes
   on `main`, build + push new images for `auth-server`, `gateway`, and all
   five resource servers. Existing single-domain overlays
   (`app`/`app-chocolate`) keep running old images unaffected until step 3.
2. **Build `app-multi/`** per §2, `secrets-patch.yaml` copied from either
   existing overlay (same secrets), DNS/CoreDNS entries for both domains
   already exist from the original `sichocolate.md` rollout (§4–§5 there) —
   no new DNS work needed, both domains already resolve to the same node.
3. **Cutover**: `kubectl apply -k cloud/hetzner/app-multi`, then restart all
   seven config-consuming deployments (same rationale as
   `sichocolate.md` §7 — `apply` doesn't restart pods on ConfigMap/image
   change by itself for anything not touched... but since this changes
   `image` tags on all of them, a normal `kubectl apply -k` **does** trigger a
   rollout for those seven automatically; only double check `kafka`/postgres
   are untouched and don't restart needlessly).
4. **Verify** (mirrors `sichocolate.md` §"Verify after switching", against
   both domains this time):
   ```
   kubectl -n granite get certificate
   curl -sI https://granite-security.org/                     # 200 from ui-shop
   curl -sI https://sichocolate.com/                           # 200 from ui-demo
   curl -s https://granite-security.org/auth/.well-known/openid-configuration | jq .issuer
   curl -s https://sichocolate.com/auth/.well-known/openid-configuration | jq .issuer
   # full login round-trip on each domain, confirm each domain's issued JWT
   # is accepted by e.g. GET https://<domain>/api/profiles/me
   ```
5. Once `app-multi` is confirmed stable, delete `cloud/hetzner/app/` and
   `cloud/hetzner/app-chocolate/` (or leave them as documented single-domain
   fallbacks — team's call, not a technical requirement either way).

## 7. Summary of file-level changes

| File | Change |
|---|---|
| `auth-server/.../SecurityConfig.java` | Optional issuer (fall through to per-request derivation); two `spa-client-*` `RegisteredClient`s instead of one |
| `auth-server/.../application.yaml` | `server.forward-headers-strategy: framework`; `AUTH_SERVER_ISSUER` becomes optional; new `SPA_CLIENT_SHOP_*`/`SPA_CLIENT_CHOCOLATE_*` vars |
| `gateway/.../application.yaml` | `server.forward-headers-strategy: framework` |
| `gateway/.../RouterConfig.java` | `indexRedirect()` → relative `/` instead of absolute `spaOrigin` |
| `payment`, `shop`, `profile`, `greetings`, `delivery` — `application.yaml` | Replace `issuer-uri` with `jwk-set-uri` |
| same 5 services — new `JwtDecoder`/`ReactiveJwtDecoder` bean | Custom multi-issuer `OAuth2TokenValidator`, `TRUSTED_JWT_ISSUERS` env var |
| `k8s/base/config.yaml` | No change (dev/kind default stays single-domain) |
| `cloud/hetzner/app-multi/` (new) | Merges `app/` + `app-chocolate/`: shared `gateway.yaml` (§3), shared `config-patch.yaml` (§1/§4), both `ui-shop` + `ui-demo` present |
| `cloud/hetzner/app/`, `cloud/hetzner/app-chocolate/` | Retired after cutover (or kept as documented single-domain fallback) |

## 8. Open questions to settle before starting implementation

- Do we want a **third**, shared `oidc-client`-style client for anything that
  needs to work across both domains, or is per-domain-only (§1c) sufficient
  for every current use case? (Nothing in the current codebase suggests a
  cross-domain client is needed — `oidc-client` is Postman-only testing today.)
- Should `granite-security.org` and `sichocolate.com` share a Google OAuth
  "app" (already true — both redirect URIs are registered on the same Google
  Cloud Console client per `sichocolate.md` §6) or get separate Google apps?
  Plan above assumes shared, no change needed.
- Retire vs. keep `app`/`app-chocolate` as fallback overlays (§6 step 5) —
  needs a team decision, not a technical one.
