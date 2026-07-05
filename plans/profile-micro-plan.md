# User Profile & Addresses Microservice — Plan

New microservice that manages user profiles (name, email) and delivery addresses, with a clean private REST API consumed by the shop and UI.

## Name: **`profile`**

Follows the project convention of single-word service names (`shop`, `payment`, `greetings`). "Profile" captures the domain: user profile data + addresses.

- Java package: `org.granitesecurity.profile`
- Docker service name: `profile`
- Port: `8064`
- DB: `profiledb` on port `5436`

---

## Design Decisions

### Relationship to auth-server

The auth-server keeps its `users` table for authentication. The **profile** service has its own `user_profile` table keyed by `username` — matching the JWT `sub` claim. No shared database, no cross-service DB access. Identity is established entirely through the JWT.

- Auth-server: owns authentication (login, tokens, password)
- Profile: owns profile data (name, email, addresses)

When a user first accesses profile endpoints, a profile record is auto-created from JWT claims (or lazily when the user saves profile data).

### Address ownership

Addresses are owned by the profile service (user domain). When an order is placed:

1. UI loads user's saved addresses from profile service (`GET /api/profiles/me/addresses`)
2. UI sends the **full address snapshot** (not just an ID) to shop in the `PlaceOrderRequest`
3. Shop embeds the address in the `OrderPlaced` event payload (self-contained event)
4. Delivery service receives the address via the event — no synchronous coupling to profile service

This avoids cross-service REST calls during the order flow while keeping the profile service as the canonical address store.

---

## Database Schema

### `user_profile`

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL` | PK |
| `username` | `VARCHAR(64)` | UNIQUE, matches JWT `sub` |
| `email` | `VARCHAR(255)` | nullable (user might not have set it) |
| `first_name` | `VARCHAR(64)` | nullable |
| `last_name` | `VARCHAR(64)` | nullable |
| `created_at` | `TIMESTAMPTZ` | |
| `updated_at` | `TIMESTAMPTZ` | |

### `delivery_address`

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL` | PK |
| `username` | `VARCHAR(64)` | FK-like (matches JWT `sub`) |
| `label` | `VARCHAR(128)` | "Home", "Work", nullable |
| `recipient_name` | `VARCHAR(255)` | NOT NULL |
| `address_line1` | `VARCHAR(255)` | NOT NULL |
| `address_line2` | `VARCHAR(255)` | nullable |
| `city` | `VARCHAR(128)` | NOT NULL |
| `state` | `VARCHAR(64)` | nullable |
| `zip_code` | `VARCHAR(16)` | NOT NULL |
| `country` | `VARCHAR(64)` | NOT NULL |
| `is_default` | `BOOLEAN` | DEFAULT FALSE |
| `created_at` | `TIMESTAMPTZ` | |
| `updated_at` | `TIMESTAMPTZ` | |

---

## REST APIs

All proxied through the gateway at `/api/profiles/**`.

### Profile

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` | `/api/profiles/me` | JWT | Get current user's profile |
| `PUT` | `/api/profiles/me` | JWT | Update current user's profile (name, email) |

### Addresses

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` | `/api/profiles/me/addresses` | JWT | List user's saved addresses |
| `POST` | `/api/profiles/me/addresses` | JWT | Save a new address |
| `PUT` | `/api/profiles/me/addresses/{id}` | JWT | Update an address |
| `DELETE` | `/api/profiles/me/addresses/{id}` | JWT | Delete an address |

### Internal (shop → profile, gateways only)

These endpoints are called by the shop service (gateway-to-service, not exposed to the UI):

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` | `/api/profiles/internal/{username}/addresses/{id}` | client-credentials | Resolve a full address by ID (used by shop when placing order) |

---

## Domain Model

### `UserProfile`

JPA-like entity (R2DBC `@Table("user_profile")`), Lombok `@Data`, `Persistable<Long>`.

### `DeliveryAddress`

R2DBC `@Table("delivery_address")`, same pattern.

### DTOs

- `ProfileResponse` — id, username, email, firstName, lastName
- `UpdateProfileRequest` — email, firstName, lastName
- `AddressRequest` — label, recipientName, addressLine1/2, city, state, zipCode, country, isDefault
- `AddressResponse` — all address fields + id

---

## Package Structure

```
profile/
├── build.gradle.kts
├── Dockerfile
├── src/main/java/org/granitesecurity/profile/
│   ├── ProfileApplication.java
│   ├── domain/
│   │   ├── UserProfile.java
│   │   └── DeliveryAddress.java
│   ├── repository/
│   │   ├── UserProfileRepository.java
│   │   └── DeliveryAddressRepository.java
│   ├── service/
│   │   ├── ProfileService.java
│   │   └── AddressService.java
│   ├── handler/
│   │   ├── ProfileHandler.java
│   │   └── AddressHandler.java
│   ├── route/
│   │   └── ProfileRoute.java
│   ├── security/
│   │   └── ProfileSec.java
│   └── config/
│       └── ProfileConfig.java (optional)
├── src/main/resources/
│   ├── application.yaml
│   └── db/changelog/
│       ├── db.changelog-master.yaml
│       └── 001-create-profile-schema.sql
└── src/test/...
```

---

## Execution Model

**Reactive (WebFlux + R2DBC)** — same as `shop` and `payment`. All services use reactive stack; profile should follow suit.

- `spring-boot-starter-webflux`
- `spring-boot-starter-data-r2dbc`
- `spring-boot-starter-security-oauth2-resource-server`
- `spring-boot-starter-liquibase` + `spring-jdbc` (for Liquibase JDBC URL)
- `postgresql` (JDBC) + `r2dbc-postgresql`
- Lombok
- No Kafka (not needed yet — profile is purely REST)

---

## Implementation Steps

### Step 1 — Scaffold the module

Copy `payment/` build structure, update for `profile`:
- `build.gradle.kts` (omit Stripe and Kafka deps)
- `Dockerfile`
- `settings.gradle.kts`: `rootProject.name = "profile"`

### Step 2 — Database changelog

```sql
--liquibase formatted sql

--changeset adrian:001-create-user-profile
CREATE TABLE user_profile (
    id         BIGSERIAL    PRIMARY KEY,
    username   VARCHAR(64)  NOT NULL UNIQUE,
    email      VARCHAR(255),
    first_name VARCHAR(64),
    last_name  VARCHAR(64),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_profile_username ON user_profile(username);

--changeset adrian:001-create-delivery-address
CREATE TABLE delivery_address (
    id             BIGSERIAL    PRIMARY KEY,
    username       VARCHAR(64)  NOT NULL,
    label          VARCHAR(128),
    recipient_name VARCHAR(255) NOT NULL,
    address_line1  VARCHAR(255) NOT NULL,
    address_line2  VARCHAR(255),
    city           VARCHAR(128) NOT NULL,
    state          VARCHAR(64),
    zip_code       VARCHAR(16)  NOT NULL,
    country        VARCHAR(64)  NOT NULL,
    is_default     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_delivery_address_username ON delivery_address(username);
```

### Step 3 — Domain entities

`UserProfile` and `DeliveryAddress` as R2DBC `@Table` entities with Lombok.

### Step 4 — Repositories

```java
public interface UserProfileRepository extends R2dbcRepository<UserProfile, Long> {
    Mono<UserProfile> findByUsername(String username);
}

public interface DeliveryAddressRepository extends R2dbcRepository<DeliveryAddress, Long> {
    Flux<DeliveryAddress> findByUsername(String username);
    Mono<DeliveryAddress> findByIdAndUsername(Long id, String username);
    Mono<Void> deleteByIdAndUsername(Long id, String username);
}
```

### Step 5 — Services

**`ProfileService`:**
- `getProfile(String username)` — get or auto-create from JWT context
- `updateProfile(String username, UpdateProfileRequest req)` — update email, first/last name

**`AddressService`:**
- `getAddresses(String username)` — list user's addresses
- `createAddress(String username, AddressRequest req)` — create (if `isDefault`, unset others)
- `updateAddress(Long id, String username, AddressRequest req)` — update
- `deleteAddress(Long id, String username)` — delete
- `getAddressByIdAndUsername(Long id, String username)` — internal: resolve address by ID for shop

### Step 6 — Handlers (functional endpoints)

Following `payment/handler/` pattern:
- `ProfileHandler`: `getMe()`, `updateMe()`
- `AddressHandler`: `listAddresses()`, `createAddress()`, `updateAddress()`, `deleteAddress()`
- Internal address resolver: `getAddressById()` (separate or same handler)

### Step 7 — Routing

```java
@Configuration
public class ProfileRoute {
    @Bean
    RouterFunction<ServerResponse> profileRoutes(ProfileHandler ph, AddressHandler ah) {
        return RouterFunctions.route()
            .GET("/api/profiles/me", ph::getMe)
            .PUT("/api/profiles/me", ph::updateMe)
            .GET("/api/profiles/me/addresses", ah::listAddresses)
            .POST("/api/profiles/me/addresses", ah::createAddress)
            .PUT("/api/profiles/me/addresses/{id}", ah::updateAddress)
            .DELETE("/api/profiles/me/addresses/{id}", ah::deleteAddress)
            .GET("/api/profiles/internal/{username}/addresses/{id}", ah::getAddressById)
            .build();
    }
}
```

### Step 8 — Security

```java
@Bean
SecurityWebFilterChain security(ServerHttpSecurity http) {
    return http
        .authorizeExchange(auth -> auth
            .pathMatchers("/api/profiles/internal/**").hasAuthority("SCOPE_internal")
            .pathMatchers("/api/profiles/**").authenticated()
            .anyExchange().permitAll()
        )
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(...))
        .build();
}
```

Internal endpoints require `SCOPE_internal` — issued by client-credentials grant to the shop service.

### Step 9 — Gateway route

In `RouterConfig.java`:
```java
@Value("${microservices.profile.uri:http://localhost:8064}")
private String profileServiceUri;

.route("profile-service", r -> r
    .path("/api/profiles/**")
    .uri(profileServiceUri))
```

### Step 10 — Gateway security

Already `.anyExchange().permitAll()` so `/api/profiles/**` passes through.

### Step 11 — Docker Compose

Add `profile-postgres` (port 5436) and `profile` service (port 8064):

```yaml
profile-postgres:
  image: 'postgres:latest'
  container_name: 'profile-postgres'
  environment:
    - 'POSTGRES_DB=profiledb'
    - 'POSTGRES_PASSWORD=secret'
    - 'POSTGRES_USER=myuser'
  ports:
    - '5436:5432'

profile:
  build:
    context: ./profile
  container_name: profile
  ports:
    - "8064:8064"
  environment:
    - SPRING_PROFILES_ACTIVE=docker
    - PROFILE_SERVER_PORT=8064
    - PROFILE_R2DBC_URL=r2dbc:postgresql://profile-postgres:5432/profiledb
    - PROFILE_R2DBC_USERNAME=myuser
    - PROFILE_R2DBC_PASSWORD=secret
    - PROFILE_JDBC_URL=jdbc:postgresql://profile-postgres:5432/profiledb
    - PROFILE_JDBC_USERNAME=myuser
    - PROFILE_JDBC_PASSWORD=secret
    - AUTH_ISSUER_URI=http://gateway:8080/auth
    - SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=http://gateway:8080/auth
  depends_on:
    gateway:
      condition: service_started
    profile-postgres:
      condition: service_started
  restart: on-failure
```

### Step 12 — Update delivery plan

The delivery address is now sourced from the **profile service** (not the shop). Update `plans/delivery.md`:
- Shop's `PlaceOrderRequest` sends `addressId` (or inline address snapshot)
- Shop calls profile service internally to resolve address (or trusts inline data from UI)
- OrderPlaced event payload includes full address snapshot

### Step 13 — UI: Address management page

New UI page/component for managing saved addresses (linked from nav or profile section).

### Step 14 — Update Checkout

Checkout UI loads addresses from profile service (`GET /api/profiles/me/addresses`), shows address picker + "Add new address" form.

---

## Files to create / modify

| File | Action |
|---|---|
| `profile/build.gradle.kts` | Create |
| `profile/settings.gradle.kts` | Create |
| `profile/Dockerfile` | Create |
| `profile/src/main/java/.../ProfileApplication.java` | Create |
| `profile/src/main/java/.../domain/UserProfile.java` | Create |
| `profile/src/main/java/.../domain/DeliveryAddress.java` | Create |
| `profile/src/main/java/.../repository/UserProfileRepository.java` | Create |
| `profile/src/main/java/.../repository/DeliveryAddressRepository.java` | Create |
| `profile/src/main/java/.../service/ProfileService.java` | Create |
| `profile/src/main/java/.../service/AddressService.java` | Create |
| `profile/src/main/java/.../handler/ProfileHandler.java` | Create |
| `profile/src/main/java/.../handler/AddressHandler.java` | Create |
| `profile/src/main/java/.../route/ProfileRoute.java` | Create |
| `profile/src/main/java/.../security/ProfileSec.java` | Create |
| `profile/src/main/resources/application.yaml` | Create |
| `profile/src/main/resources/db/changelog/db.changelog-master.yaml` | Create |
| `profile/src/main/resources/db/changelog/001-create-profile-schema.sql` | Create |
| `gateway/.../config/RouterConfig.java` | Modify (add profile route) |
| `compose.yaml` | Modify (add profile service + DB) |
| `plans/delivery.md` | Modify (address sourced from profile) |
| `ui-shop/src/pages/Checkout.tsx` | Modify (load addresses, show picker) |
| `ui-shop/src/pages/OrderDetail.tsx` | Modify (show delivery address) |
| `ui-shop/src/api.ts` | Modify (add profile/address API calls) |
| `ui-shop/src/types.ts` | Modify (add address types) |
