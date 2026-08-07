# gateway

Port **8080**. Spring Cloud Gateway (WebFlux). The single entry point for both
storefronts: it routes by path and nothing else. It holds no state, no session,
and no token.

## API

The gateway exposes no API of its own beyond a liveness route. Everything else
is a proxy target.

| Path | Proxied to | Auth enforced by |
|------|------------|------------------|
| `/api/greetings/**`, `/api/secured/**` | greetings 8060 | greetings |
| `/api/shop/**` | shop 8061 | shop |
| `/api/payments/**` | payment 8062 | payment |
| `/api/delivery/**` | delivery 8063 | delivery |
| `/api/profiles/**` | profile 8064 | profile |
| `/api/storage/**` | storage 8065 | storage |
| `/api/balance/**` | balance 8067 | balance |
| `/auth/**` | auth-server 9090 | auth-server |
| `/v3/api-docs/**`, `/swagger-ui/**` | shop 8061 | — |
| `GET /` | — | returns `200 gateway is up` |

Routes live in `RouterConfig`; 

`GET /` exists because nginx never proxies `/` — it fires only when something
reaches the gateway directly, so a plain 200 is enough. It deliberately does
not redirect to a frontend: there are two, and picking one would be wrong.

### Authorization

`GateSec` is `anyExchange().permitAll()`. The gateway is **not** an OAuth2
client: it obtains no token, holds no session, and attaches nothing. The SPA
runs authorization code + PKCE itself and sends its own `Authorization: Bearer`
header, which the gateway forwards untouched.

Every route above is therefore as protected as its downstream service makes it,
and no more. **Adding a route here grants access; it does not restrict it.**

Spring Security is still on the classpath for one reason: its default header
writers add `X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy` and
the no-store `Cache-Control` to every response. That is what `GateSec` and
`spring-boot-starter-security` buy.

## Events

**None.** No Kafka, no database, no scheduled work.

## Configuration

| Variable | Default | Purpose |
|----------|---------|---------|
| `MICROSERVICES_<SERVICE>_URI` | `http://localhost:<port>` | Proxy target per service |
| `SPA_MICROSERVICE` | `http://localhost:5173` | SPA origin |

`application.yaml` carries three decisions that cost real production time.
Read the comments there before changing it:

- **Connection pool** (`max-idle-time` 15s, `max-life-time` 60s). Pooled
  connections outlive the pods behind them; after a deploy the gateway keeps
  dead connections and 500s until the pool turns over. `GatewayConfigBindingTest`
  asserts these bind, because Gateway 5.x silently ignores the old
  `spring.cloud.gateway.*` prefix where they used to sit and do nothing.
- **`server.forward-headers-strategy` is unset on purpose.** Setting it strips
  `X-Forwarded-Host` before it reaches auth-server, which then derives its
  issuer from an internal URI.
- **`globalcors` is absent on purpose.** Both SPAs are same-origin with the API,
  so nothing needs it.

```bash
./gradlew bootRun
./gradlew build -x test
```
