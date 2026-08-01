# Running Granite Security in Kind

This guide runs all microservices from this repo inside a local [kind](https://kind.sigs.k8s.io) cluster, using [Kustomize](https://kustomize.io) (built into `kubectl`) for environment-aware configuration.

---

## Architecture Overview

| Service | Port in K8s | Stack | Role |
|---------|-------------|-------|------|
| `postgres-auth` | 5432 | PostgreSQL 17 | Auth server database |
| `postgres-shop` | 5432 | PostgreSQL latest | Shop database |
| `postgres-payment` | 5432 | PostgreSQL latest | Payment database |
| `postgres-delivery` | 5432 | PostgreSQL latest | Delivery database |
| `postgres-profile` | 5432 | PostgreSQL latest | Profile database |
| `kafka` | 9092 | Confluent Kafka (KRaft) | Event broker |
| `auth-server` | 9090 | Spring Authorization Server | OIDC provider |
| `gateway` | 8080 | Spring Cloud Gateway | Internal reverse proxy / token relay (ClusterIP) |
| `greetings` | 8060 | Spring WebFlux | Demo endpoints |
| `shop` | 8061 | Spring WebFlux + R2DBC | E-commerce catalog + orders |
| `payment` | 8062 | Spring WebFlux + R2DBC + Stripe | Payment processing |
| `profile` | 8064 | Spring WebFlux + R2DBC | User profiles |
| `delivery` | 8063 | Spring WebFlux + R2DBC | Delivery tracking |
| `ui-shop` | 80 | nginx serving React SPA | Browser front door (NodePort 30080) — serves SPA, proxies to gateway |

All resources live in the `granite` namespace. Services discover each other via K8s DNS (short names work within the namespace: `postgres-auth`, `gateway`, etc.).

---

## 1. Prerequisites

- [kind](https://kind.sigs.k8s.io/docs/user/quick-start/#installation) (`brew install kind`)
- [kubectl](https://kubernetes.io/docs/tasks/tools/) (`brew install kubectl`)
- Docker
- Java 25 (for building JARs, or let the Dockerfiles do it)
- Stripe keys (optional)
- Google OAuth2 credentials (optional)

---

## 2. Create the kind Cluster

```bash
cd k8s/kind

kind create cluster --config kind-config.yaml --name granite
```

**Host prerequisite (browser side of the shared issuer host):** add `gateway` to your
machine's hosts file so the browser reaches the ui-shop NodePort front door (this mirrors
the compose `README`, which adds `127.0.0.1 auth greetings gateway`):

```bash
echo '127.0.0.1 gateway' | sudo tee -a /etc/hosts
```

> Why `gateway` and not `granite.localhost`? Any `*.localhost` host is special-cased to
> `127.0.0.1` by curl/musl/browsers (RFC 6761), which bypasses DNS — so in-cluster pods
> could never reach the issuer. `gateway` resolves natively inside the cluster and via
> `/etc/hosts` in the browser, keeping the `iss` string identical on both sides.

Verifies `localhost:8080` → ui-shop's NodePort 30080 (the browser front door):

```bash
kubectl cluster-info --context kind-granite
```

---

## 3. Build Docker Images

Build JARs first (skip tests for speed):

```bash
(cd auth-server && ./gradlew build -x test)
(cd gateway     && ./gradlew build -x test)
(cd greetings   && ./gradlew build -x test)
(cd shop        && ./gradlew build -x test)
(cd payment     && ./gradlew build -x test)
(cd profile     && ./gradlew build -x test)
(cd delivery    && ./gradlew build -x test)
```

Then build Docker images (one per service):

```bash
docker build -t granite-auth-server:latest auth-server/
docker build -t granite-gateway:latest     gateway/
docker build -t granite-greetings:latest   greetings/
docker build -t granite-shop:latest        shop/
docker build -t granite-payment:latest     payment/
docker build -t granite-profile:latest     profile/
docker build -t granite-delivery:latest    delivery/
docker build -t granite-ui-shop:latest     ui-shop/
```

---

## 4. Load Images Into kind

```bash
kind load docker-image --name granite \
  granite-auth-server:latest \
  granite-gateway:latest \
  granite-greetings:latest \
  granite-shop:latest \
  granite-payment:latest \
  granite-profile:latest \
  granite-delivery:latest \
  granite-ui-shop:latest
```
kind load docker-image --name granite \
granite-ui-shop:latest
---

## 5. Deploy with Kustomize

### 5.1 Apply everything

```bash
kubectl apply -k k8s/kind
```
```bash
kubectl delete -k k8s/kind
```

This single command:
1. Reads `k8s/kind/kustomization.yaml`
2. Pulls in all resources from `k8s/base/`
3. Applies patches from `k8s/kind/ui-shop-patch.yaml` (NodePort front door) and `k8s/kind/config-patch.yaml` (issuer URLs)
4. Sets `namespace: granite` on every resource

**Pod side of the shared issuer host:** no action needed — pods resolve `gateway`
natively via K8s DNS within the `granite` namespace.

### 5.2 Wait for readiness

Watch all pods come up:

```bash
kubectl -n granite get pods -w
```

Wait for infrastructure first, then services:

```bash
kubectl -n granite wait --for=condition=ready pod -l app=postgres-auth --timeout=60s
kubectl -n granite wait --for=condition=ready pod -l app=kafka --timeout=60s
kubectl -n granite wait --for=condition=ready pod -l app=auth-server --timeout=120s
kubectl -n granite wait --for=condition=ready pod -l app=gateway --timeout=120s
```

### 5.3 Set secrets (Stripe, Google OAuth)

Edit the base secrets file and re-apply:

```bash
# Fill in real values
vim k8s/base/secrets.yaml

kubectl apply -k k8s/kind
```

Restart pods that need the new secrets:

```bash
kubectl -n granite rollout restart deploy/payment
```

---

## 6. Per-Service Environment Variables

Every env var below is defined in one of three places:

| Source | What it holds |
|--------|--------------|
| `k8s/base/config.yaml` (ConfigMap) | Non-sensitive env vars shared across environments |
| `k8s/base/secrets.yaml` (Secret) | Passwords, keys, tokens |
| `k8s/kind/config-patch.yaml` (ConfigMap patch) | kind-specific overrides (issuer URL, login redirect) |

### 6.1 auth-server (`auth-server.yaml`)

| Env Var | Source | Value | Notes |
|---------|--------|-------|-------|
| `SERVER_PORT` | ConfigMap | `9090` | |
| `SPRING_DATASOURCE_URL` | Hardcoded in manifest | `jdbc:postgresql://postgres-auth:5432/authdb` | JDBC, not R2DBC |
| `SPRING_DATASOURCE_USERNAME` | Hardcoded | `postgres` | |
| `SPRING_DATASOURCE_PASSWORD` | Secret `db-postgres-password` | `postgres` | |
| `AUTH_SERVER_ISSUER` | ConfigMap (patched in kind) | `http://gateway:8080/auth` | ⚠ **Must match** what resource servers use for `issuer-uri` |
| `APP_LOGIN_PAGE_URL` | ConfigMap (patched in kind) | `http://gateway:8080/auth/login` | Browser-accessible redirect target |
| `OIDC_CLIENT_SECRET_ENCODED` | Secret `oidc-client-secret-encoded` | `{noop}secret` | `{noop}` tells Spring to use plain-text |
| `OIDC_CLIENT_REDIRECT_URI` | ConfigMap (patched in kind) | `http://gateway:8080/login/oauth2/code/oidc-client` | Gateway's OAuth2 callback |
| `OIDC_CLIENT_POST_LOGOUT_REDIRECT_URI` | ConfigMap (patched in kind) | `http://gateway:8080/` | Where browser goes after logout |
| `SPA_CLIENT_REDIRECT_URI` | ConfigMap (patched in kind) | `http://gateway:8080/callback` | SPA (public PKCE client) callback |
| `GOOGLE_CLIENT_ID` | Secret `google-client-id` | _(set by you)_ | Leave empty to skip Google login |
| `GOOGLE_CLIENT_SECRET` | Secret `google-client-secret` | _(set by you)_ | Leave empty to skip Google login |

**Registered OAuth2 Clients** (in-memory in auth-server's Java code):

| Client ID | Type | Secret | Grant Types | PKCE |
|-----------|------|--------|-------------|------|
| `oidc-client` | Confidential | `{noop}secret` | authorization_code, refresh_token | Optional |
| `spa-client` | Public | _(none)_ | authorization_code, refresh_token | Required |
| `external-service` | Confidential | _(hardcoded)_ | client_credentials | — |

### 6.2 gateway (`gateway.yaml`)

| Env Var | Source | Value | Notes |
|---------|--------|-------|-------|
| `SERVER_PORT` | ConfigMap | `8080` | |
| `MICROSERVICES_AUTH_SERVER_URI` | ConfigMap | `http://auth-server:9090` | Proxies `/auth/**` |
| `MICROSERVICES_GREETINGS_URI` | ConfigMap | `http://greetings:8060` | Proxies `/api/greetings/**`, `/api/secured/**` |
| `MICROSERVICES_SHOP_URI` | ConfigMap | `http://shop:8061` | Proxies `/api/shop/**`, `/swagger-ui/**`, `/v3/api-docs/**` |
| `MICROSERVICES_PAYMENT_URI` | ConfigMap | `http://payment:8062` | Proxies `/api/payments/**` |
| `MICROSERVICES_PROFILE_URI` | ConfigMap | `http://profile:8064` | Proxies `/api/profiles/**` |
| `MICROSERVICES_DELIVERY_URI` | ConfigMap | `http://delivery:8063` | Proxies `/api/delivery/**` |
| `MICROSERVICES_SPA_URI` | ConfigMap | `http://ui-shop:80` | Root `/` redirects here |

### 6.3 greetings (`greetings.yaml`)

| Env Var | Source | Value | Notes |
|---------|--------|-------|-------|
| `SERVER_PORT` | ConfigMap | `8060` | |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | ConfigMap (patched) | `http://gateway:8080/auth` | ⚠ Must match `AUTH_SERVER_ISSUER` |

**Routes:** `/api/greetings/**` public, `/api/catalog/**` requires ROLE_USER/ROLE_ADMIN.

### 6.4 shop (`shop.yaml`)

| Env Var | Source | Value | Notes |
|---------|--------|-------|-------|
| `SERVER_PORT` | ConfigMap | `8061` | |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | ConfigMap (patched) | `http://gateway:8080/auth` | ⚠ Must match `AUTH_SERVER_ISSUER` |
| `SHOP_R2DBC_URL` | ConfigMap | `r2dbc:postgresql://postgres-shop:5432/shopdb` | Reactive DB connection |
| `SHOP_R2DBC_USERNAME` | Hardcoded | `myuser` | |
| `SHOP_R2DBC_PASSWORD` | Secret `db-myuser-password` | `secret` | |
| `SHOP_JDBC_URL` | ConfigMap | `jdbc:postgresql://postgres-shop:5432/shopdb` | Liquibase migrations |
| `SHOP_JDBC_USERNAME` | Hardcoded | `myuser` | |
| `SHOP_JDBC_PASSWORD` | Secret `db-myuser-password` | `secret` | |
| `KAFKA_BOOTSTRAP_SERVERS` | ConfigMap | `kafka:9092` | Producer + consumer |
| `PAYMENT_SERVICE_URI` | ConfigMap | `http://payment:8062` | Calls payment synchronously for order flow |

**Routes:** See README or `shop/src/main/java/.../ShopSec.java`.

### 6.5 payment (`payment.yaml`)

| Env Var | Source | Value | Notes |
|---------|--------|-------|-------|
| `SERVER_PORT` | ConfigMap | `8062` | |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | ConfigMap (patched) | `http://gateway:8080/auth` | ⚠ Must match `AUTH_SERVER_ISSUER` |
| `PAYMENT_R2DBC_URL` | ConfigMap | `r2dbc:postgresql://postgres-payment:5432/paymentdb` | |
| `PAYMENT_R2DBC_USERNAME` | Hardcoded | `myuser` | |
| `PAYMENT_R2DBC_PASSWORD` | Secret `db-myuser-password` | `secret` | |
| `PAYMENT_JDBC_URL` | ConfigMap | `jdbc:postgresql://postgres-payment:5432/paymentdb` | Liquibase |
| `PAYMENT_JDBC_USERNAME` | Hardcoded | `myuser` | |
| `PAYMENT_JDBC_PASSWORD` | Secret `db-myuser-password` | `secret` | |
| `KAFKA_BOOTSTRAP_SERVERS` | ConfigMap | `kafka:9092` | |
| `STRIPE_SECRET_KEY` | Secret `stripe-secret-key` | _(set by you)_ | **Required** (`sk_test_...`) |
| `STRIPE_WEBHOOK_SECRET` | Secret `stripe-webhook-secret` | _(set by you)_ | For Stripe webhooks (`whsec_...`) |
| `STRIPE_CURRENCY` | ConfigMap | `chf` | |

**Routes:** `/api/payments/webhook/**` and `/api/payments/providers` public, `/api/payments/intent/**` public, everything else authenticated.

### 6.6 profile (`profile.yaml`)

| Env Var | Source | Value | Notes |
|---------|--------|-------|-------|
| `SERVER_PORT` | ConfigMap | `8064` | |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | ConfigMap (patched) | `http://gateway:8080/auth` | |
| `PROFILE_R2DBC_URL` | ConfigMap | `r2dbc:postgresql://postgres-profile:5432/profiledb` | |
| `PROFILE_R2DBC_USERNAME` | Hardcoded | `myuser` | |
| `PROFILE_R2DBC_PASSWORD` | Secret `db-myuser-password` | `secret` | |
| `PROFILE_JDBC_URL` | ConfigMap | `jdbc:postgresql://postgres-profile:5432/profiledb` | |
| `PROFILE_JDBC_USERNAME` | Hardcoded | `myuser` | |
| `PROFILE_JDBC_PASSWORD` | Secret `db-myuser-password` | `secret` | |

### 6.7 delivery (`delivery.yaml`)

| Env Var | Source | Value | Notes |
|---------|--------|-------|-------|
| `SERVER_PORT` | ConfigMap | `8063` | |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | ConfigMap (patched) | `http://gateway:8080/auth` | |
| `DELIVERY_R2DBC_URL` | ConfigMap | `r2dbc:postgresql://postgres-delivery:5432/deliverydb` | |
| `DELIVERY_R2DBC_USERNAME` | Hardcoded | `myuser` | |
| `DELIVERY_R2DBC_PASSWORD` | Secret `db-myuser-password` | `secret` | |
| `DELIVERY_JDBC_URL` | ConfigMap | `jdbc:postgresql://postgres-delivery:5432/deliverydb` | |
| `DELIVERY_JDBC_USERNAME` | Hardcoded | `myuser` | |
| `DELIVERY_JDBC_PASSWORD` | Secret `db-myuser-password` | `secret` | |
| `KAFKA_BOOTSTRAP_SERVERS` | ConfigMap | `kafka:9092` | |

### 6.8 ui-shop (`ui-shop.yaml`)

| Env Var | Source | Value | Notes |
|---------|--------|-------|-------|
| `OIDC_AUTHORITY` | ConfigMap (patched in kind) | `http://gateway:8080/auth` | Rendered into `/config.js` at container start; must equal `AUTH_SERVER_ISSUER` |
| `OIDC_CLIENT_ID` | ConfigMap | `spa-client` | Public PKCE client id |
| `STRIPE_PUBLISHABLE_KEY` | Secret `stripe-publishable-key` | _(set by you)_ | Stripe publishable key (`pk_test_...`) |

The SPA is served by nginx on port 80 and is the **browser front door** (NodePort 30080 → host `:8080`). Its `nginx.conf` proxies `/api/`, `/auth/`, `/oauth2/` to `http://gateway:8080`. Runtime config (`OIDC_AUTHORITY`, `OIDC_CLIENT_ID`, `STRIPE_PUBLISHABLE_KEY`) is rendered into `/config.js` at container start via `envsubst` (no build-time baking).

---

## 7. Login Flow in K8s

```
Browser ──GET http://gateway:8080/auth/oauth2/authorize?...──┐
                                                               │
                                                               ▼
                                              ui-shop nginx (NodePort 30080)
                                                               │ proxy /auth/ → gateway:8080
                                                               ▼ proxy /auth/
                                                     auth-server:9090
                                                               │
                                                    ┌──────────┘
                                                    ▼ not authenticated
                                                    302 → APP_LOGIN_PAGE_URL
                                                    → http://gateway:8080/auth/login
                                                    │
                                                    ▼
Browser follows redirect ──> http://gateway:8080/auth/login
                              │
                   ui-shop nginx → gateway → auth-server → login page
```

**The two critical env vars and why they differ:**

| Variable | In kind | Why this value |
|----------|---------|----------------|
| `AUTH_SERVER_ISSUER` | `http://gateway:8080/auth` | One shared host resolvable from BOTH sides: the browser (via `/etc/hosts` → 127.0.0.1 → ui-shop NodePort) and the pods (natively via K8s DNS → gateway Service). The `iss` string stays byte-identical everywhere. |
| `APP_LOGIN_PAGE_URL` | `http://gateway:8080/auth/login` | Same shared host, reached by the browser through the ui-shop nginx proxy. |

These were separated in a previous fix — originally the issuer was used for both JWT validation AND login redirects, but the Docker hostname (`gateway:8080/auth`) was unreachable from the browser.

---

## 8. Accessing Everything

| Resource | URL | How it reaches K8s |
|----------|-----|--------------------|
| SPA storefront (front door) | `http://gateway:8080/` | ui-shop NodePort 30080 mapped to host `:8080` |
| Auth login | `http://gateway:8080/auth/login` | Browser → ui-shop nginx → gateway → auth-server |
| Swagger UI | `http://gateway:8080/swagger-ui/index.html` | Browser → ui-shop nginx → gateway → shop:8061 |
| APIs | `http://gateway:8080/api/...` | Browser → ui-shop nginx → gateway → service |
| Seed users | `user`/`user`, `admin`/`admin`, `manager`/`manager` | Form login at auth-server |

To test APIs directly:

```bash
# Health check through gateway
curl -s http://localhost:8080/api/greetings/public | jq .

# Get a JWT programmatically
curl -s -X POST http://localhost:8080/auth/oauth2/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=spa-client" \
  -d "grant_type=password" \
  -d "username=admin&password=admin" | jq -r '.access_token'
```

---

## 9. Quick Teardown & Iteration

```bash
# Delete everything in the granite namespace
kubectl delete namespace granite

# Or just restart a single service after code changes
docker build -t granite-shop:latest shop/
kind load docker-image --name granite granite-shop:latest
kubectl -n granite delete pod -l app=shop

# Full rebuild
kubectl delete namespace granite
# ... rebuild images ...
# ... load images ...
kubectl apply -k k8s/kind
```

---

## 10. Customizing for Other Environments

The Kustomize structure supports adding more overlays:

```
k8s/
├── base/              # Shared — all manifests, ConfigMap with defaults
│   ├── kustomization.yaml
│   ├── config.yaml
│   ├── secrets.yaml
│   ├── auth-server.yaml
│   ├── gateway.yaml
│   └── ...
├── kind/              # Overlay #1 — kind (NodePort, localhost URLs)
│   ├── kustomization.yaml
│   ├── gateway-patch.yaml
│   ├── config-patch.yaml
│   └── kind.md
├── production/        # Overlay #2 — production (Ingress, real domain, HPA)
│   ├── kustomization.yaml
│   ├── ingress-patch.yaml
│   ├── config-patch.yaml
│   └── ...
└── development/       # Overlay #3 — development (tilt, hot-reload)
    └── ...
```

New overlays simply patch the ConfigMap values (`AUTH_SERVER_ISSUER`, `APP_LOGIN_PAGE_URL`, etc.) and adjust the gateway Service type.

---

## 11. Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| `CrashLoopBackOff` on auth-server | PostgreSQL not ready | `kubectl -n granite wait pod -l app=postgres-auth --for=condition=ready` |
| `502 Bad Gateway` on login | Gateway can't reach auth-server | Check `kubectl -n granite logs deploy/gateway` |
| `Issuer did not match` in service logs | `AUTH_SERVER_ISSUER` != `issuer-uri` | Run `kubectl -n granite get configmap granite-config -o yaml \| grep ISSUER` |
| Kafka consumers not connecting | Wrong advertised listener | Verify KAFKA_ADVERTISED_LISTENERS in base/kafka.yaml |
| Login loops / white screen | `APP_LOGIN_PAGE_URL` unreachable from browser | Must be `http://gateway:8080/auth/login` and `127.0.0.1 gateway` present in `/etc/hosts` |
| Resource server `UnknownHostException` for the issuer | Wrong issuer host (e.g. a `*.localhost` host) | Issuer must be `gateway` (resolvable in-cluster). Verify `kubectl -n granite get configmap granite-config -o yaml \| grep ISSUER` |
| `StripeInvalidRequestError` | Missing or invalid Stripe keys | Update `k8s/base/secrets.yaml` and `kubectl apply -k k8s/kind` |

---

## 12. File Reference

| File | Purpose |
|------|---------|
| `k8s/kind/kind-config.yaml` | kind cluster definition (port 8080 → NodePort 30080) |
| `k8s/kind/kind.md` | This file |
| `k8s/kind/kustomization.yaml` | Kustomize overlay — pulls base + patches |
| `k8s/kind/ui-shop-patch.yaml` | Patches ui-shop Service to NodePort (browser front door) |
| `k8s/kind/config-patch.yaml` | Overrides issuer URLs for kind |
| `k8s/base/kustomization.yaml` | Kustomize base — lists all resources + sets namespace |
| `k8s/base/namespace.yaml` | Granite namespace |
| `k8s/base/postgres.yaml` | 5 PostgreSQL Deployments + PVCs + Services |
| `k8s/base/kafka.yaml` | Kafka KRaft broker Deployment + PVC + Service |
| `k8s/base/config.yaml` | ConfigMap with all env vars (internal defaults) |
| `k8s/base/secrets.yaml` | Secret with passwords and API keys (edit me!) |
| `k8s/base/auth-server.yaml` | Auth server Deployment + Service + probes |
| `k8s/base/gateway.yaml` | Gateway Deployment + Service (ClusterIP) + probes (probes hit local `/`) |
| `k8s/base/greetings.yaml` | Greetings Deployment + Service |
| `k8s/base/shop.yaml` | Shop Deployment + Service |
| `k8s/base/payment.yaml` | Payment Deployment + Service |
| `k8s/base/profile.yaml` | Profile Deployment + Service |
| `k8s/base/delivery.yaml` | Delivery Deployment + Service |
| `k8s/base/ui-shop.yaml` | React SPA (nginx) Deployment + Service |
