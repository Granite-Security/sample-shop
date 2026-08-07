# greetings microservice

Port **8060**. The minimal reference implementation of the conventions every
other service in this platform follows: Spring WebFlux, functional routing
(`RouterFunction` + handler classes, never `@RestController`), and OAuth2
resource-server JWT validation done by the service itself.

It exists to answer "does auth work end to end?" without dragging any dependencies. It owns no state.

## API

Two endpoints, both `GET`, both returning `text/plain`.

| Endpoint | Auth | Response |
|----------|------|----------|
| `GET /api/greetings` | none — explicitly permit-all | `Hello, World!` |
| `GET /api/secured` | Bearer JWT required | Greeting naming the subject, plus their granted authorities |

Through the gateway, both are reachable at `http://localhost:8080` on the same
paths (`RouterConfig` routes `/api/greetings/**` and `/api/secured/**` here).
The gateway is a pass-through proxy — it does not attach a token, so the caller
supplies its own `Authorization: Bearer` header, which is what `ui-shop` does.

```bash
curl http://localhost:8080/api/greetings
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/secured
```

`/api/secured` returns 401 without a token and echoes the authorities it derived
from the JWT — the fastest way to confirm the `roles` claim survived the trip
from `auth-server`. It also logs the full decoded token at INFO, which is
deliberate for a demo service and the reason nothing sensitive should be issued
against a deployment you don't control.

### How the token is validated

`GreetingsSec` fetches the signing keys from `JWT_JWK_SET_URI` (defaults to
`http://localhost:9090/auth/oauth2/jwks`) and accepts a token only when its
`iss` claim appears in the `TRUSTED_JWT_ISSUERS` allow-list, alongside the
standard expiry check. The JWKS host is internal and domain-agnostic; the
issuer is not — which is why validation is split this way rather than using a
single `issuer-uri`. Authorities come from two places: `scope` becomes
`SCOPE_*`, and the custom `roles` claim `auth-server` injects becomes `ROLE_*`.

Because `auth-server` regenerates its RSA key pair on every startup, tokens
minted before a restart stop verifying here. A sudden 401 usually means
`auth-server` restarted, not that anything in this service changed.

## Events

**None.** 

## Configuration

| Variable | Default | Purpose |
|----------|---------|---------|
| `SERVER_PORT` | `8060` | HTTP port |
| `JWT_JWK_SET_URI` | `http://localhost:9090/auth/oauth2/jwks` | Where signing keys are fetched |
| `TRUSTED_JWT_ISSUERS` | `http://localhost:8080/auth` | Comma-separated allow-list of accepted `iss` values |

## Running
(Optional) Start the auth-server
```bash
./gradlew bootRun     # needs auth-server up for /api/secured
./gradlew build -x test
```
