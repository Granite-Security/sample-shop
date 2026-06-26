## CORS fixes (gateway + ui-shop)

- **`gateway/.../application.yaml`** — `globalcors` `allowed-origin-patterns` was a comma-separated scalar, not a YAML list; changed to proper list format under `allowed-origins`.
- **`gateway/.../GateSec.java`** — Removed the redundant `CorsConfigurationSource` bean (conflicted with YAML `globalcors`) and its `.cors()` call in the security chain.
- **`ui-shop/vite.config.ts`** — Added Vite dev proxy (`/api` → `localhost:8080`) so API calls stay same-origin during development.
- **`ui-shop/src/api.ts`** — Changed `BASE` from `http://localhost:8080` to `''` to use relative URLs through the proxy.

## Auth server behind the gateway

### Goal

Route the authorization server through the gateway so all services are accessed from a single origin (`localhost:8080`):

```
/auth/**  → auth-server:9090
/api/**   → backend services
/         → SPA
```

### Changes

#### Gateway

- **`gateway/.../config/RouterConfig.java`** —
  - Injected `authServerUri` from `microservices.auth-server.uri` (default `http://localhost:9090`).
  - Added route `"auth-server"` (`/auth/**` → auth-server). No `tokenRelay` — the auth-server handles its own authentication.

- **`gateway/.../config/GateSec.java`** —
  - Added `.pathMatchers("/auth/**").permitAll()` so the gateway's security doesn't intercept auth-server traffic (login page, OAuth2 endpoints, OIDC discovery).

- **`gateway/.../resources/application.yaml`** —
  - Default `OIDC_ISSUER_URI` changed from `http://localhost:9090` to `http://localhost:8080/auth`.
  - Added `microservices.auth-server.uri` config key.

#### Auth server

- **`auth-server/.../resources/application.yaml`** —
  - Added `server.servlet.context-path: /auth` — all auth-server endpoints are under `/auth/`.
  - Added `server.forward-headers-strategy: framework` — respects `X-Forwarded-*` headers set by the gateway.
  - Default issuer changed from `http://localhost:9090` to `http://localhost:8080/auth`.

- **`auth-server/.../SecurityConfig.java`** —
  - Changed `LoginUrlAuthenticationEntryPoint("/login")` to `LoginUrlAuthenticationEntryPoint(issuer + "/login")` so the login redirect uses the gateway URL (`http://localhost:8080/auth/login`) instead of constructing it from the raw request host:port.

#### Greetings (resource server)

- **`greetings/.../resources/application.yaml`** —
  - Default `issuer-uri` fallback changed from `http://localhost:9090` to `http://localhost:8080/auth`.

- **`greetings/.../handler/SecuredGreetingsHandlerTest.java`** (pre-existing bug) —
  - Added missing mock for `authentication.getCredentials()` that was causing an NPE.

#### Docker compose

- **`compose.yaml`** (root) —
  - **auth-server**: `AUTH_SERVER_ISSUER=http://gateway:8080/auth`, healthcheck → `/auth/login`.
  - **gateway**: `OIDC_ISSUER_URI=http://gateway:8080/auth` (removed old `OIDC_AUTHORIZATION_URI` override), added `AUTH_SERVER_MICROSERVICE=http://auth-server:9090`.
  - **greetings/shop/payment**: `AUTH_ISSUER_URI` and `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` → `http://gateway:8080/auth`, depend on `gateway` instead of `auth-server`.

### How the login flow works

1. User visits `http://localhost:8080/api/secured`
2. Gateway (unauthenticated) → 302 redirect to `http://localhost:8080/auth/oauth2/authorize`
3. Gateway proxies `/auth/**` → auth-server at `http://auth-server:9090/auth/**`
4. Auth-server sees no session → 302 to `http://localhost:8080/auth/login` (absolute URL from configured issuer)
5. User logs in via auth-server's form login or Google OAuth2
6. Auth-server redirects back to `http://localhost:8080/login/oauth2/code/oidc-client?code=...`
7. Gateway exchanges code for tokens, creates session
8. JWT `iss` claim = `http://localhost:8080/auth`; resource servers validate against the same issuer
