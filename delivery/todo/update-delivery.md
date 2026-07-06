# UI Delivery Status Management (Admins & Managers)

## Goal
Allow admins and managers to change delivery statuses from the UI. Only users with `ROLE_ADMIN` or `ROLE_MANAGER` should have access.

---

## Background: How Roles Flow

The auth-server includes a `roles` claim in every JWT with all authority strings from the DB. For example, a `manager` user's JWT has:

```json
"roles": ["ROLE_USER", "USER", "ROLE_ADMIN", "ADMIN", "MANAGER"]
```

Other services (shop, payment, profile, greetings) use a **custom `ReactiveJwtAuthenticationConverter`** that maps:
- `scope` claim → `SCOPE_*` authorities (default Spring behavior)
- `roles` claim → `ROLE_*` authorities (custom)

So the bare `"ADMIN"` becomes `ROLE_ADMIN`, matching `hasRole("ADMIN")`.

**Delivery service currently uses the default `ReactiveJwtAuthenticationConverter`**, so only `SCOPE_*` authorities are available — role-based gating won't work.

---

## Changes Required

### Step 1: Add custom JWT authentication converter to delivery service

**New file:** `delivery/src/main/java/org/granitesecurity/delivery/security/JwtAuthConverter.java`

Copy the same pattern used in other services (e.g. `ShopSec`, `GreetingsSec`):

```java
package org.granitesecurity.delivery.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class JwtAuthConverter {

    @Bean
    public ReactiveJwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopesConverter = new JwtGrantedAuthoritiesConverter();
        Converter<Jwt, Mono<AbstractAuthenticationToken>> converter = jwt -> {
            List<GrantedAuthority> authorities = new ArrayList<>();
            authorities.addAll(scopesConverter.convert(jwt));
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null) {
                roles.stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .forEach(authorities::add);
            }
            return Mono.just(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt, authorities));
        };
        ReactiveJwtAuthenticationConverter converterBean = new ReactiveJwtAuthenticationConverter();
        converterBean.setJwtAuthenticationConverter(converter);
        return converterBean;
    }
}
```

**Then update `DeliverySec.java`:**
- Remove the `jwtAuthenticationConverter()` bean from here (it's now in `JwtAuthConverter`)
- Add role-gating for the status update endpoint

```java
.pathMatchers(PUT, "/api/delivery/{orderId}/status").hasAnyRole("ADMIN", "MANAGER")
.pathMatchers("/api/delivery/**").authenticated()
```

### Step 2: Update `DeliverySec.java`

Current:
```java
.authorizeExchange(exchanges -> exchanges
        .anyExchange().authenticated()
)
```

New:
```java
.authorizeExchange(exchanges -> exchanges
        .pathMatchers(PUT, "/api/delivery/{orderId}/status").hasAnyRole("ADMIN", "MANAGER")
        .pathMatchers("/api/delivery/**").authenticated()
)
```

Add the import:
```java
import org.springframework.http.HttpMethod;
```

### Step 3: Add `GET /api/delivery` for admin listing

Already exists from the refactoring — the route was added as:
```
GET /api/delivery?status=&paymentStatus=
```

But currently all authenticated users can access it. We should also restrict it to admins/managers, OR keep it open but only show the status-change button on the UI side based on roles.

**Design decision**: Keep `GET /api/delivery` open to all authenticated users (the data itself isn't sensitive). The status change action (`PUT`) is where authorization matters.

### Step 4: Create delivery management UI page

**New file:** `ui-shop/src/pages/DeliveryManagement.tsx`

Features:
- Fetches `GET /api/delivery` to list all deliveries
- Shows each delivery: orderId, status, paymentStatus, items, address, recipient
- For each `PENDING` delivery, shows a "Mark as Dispatched" button
- For each `DISPATCHED` delivery, shows "Mark as Delivered" and "Mark as Failed" buttons
- Uses `PUT /api/delivery/{orderId}/status` with body `{status, description}`
- Only shown to users with `ROLE_ADMIN` or `ROLE_MANAGER`

Layout idea:
```
┌─────────────────────────────────────────────┐
│ Delivery Management                          │
│                                              │
│ ┌─────────────────────────────────────────┐  │
│ │ Order #5  |  DISPATCHED  |  PAID        │  │
│ │ Alice     | 123 Main St  |  2 items     │  │
│ │ [Mark Delivered] [Mark Failed]          │  │
│ ├─────────────────────────────────────────┤  │
│ │ Order #4  |  PENDING     |  PAID        │  │
│ │ Bob       | 456 Oak Ave  |  1 item      │  │
│ │ [Mark Dispatched]                       │  │
│ ├─────────────────────────────────────────┤  │
│ │ Order #3  |  PENDING     |  UNPAID      │  │
│ │ Charlie   | 789 Pine Rd  |  3 items     │  │
│ │ [Mark Dispatched]  (payment not cleared)│  │
│ └─────────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
```

### Step 5: Wire the new page into routing

**Update `ui-shop/src/App.tsx`** (or wherever routes are defined):
- Add a route for `/admin/deliveries` → `<DeliveryManagement />`
- The page should check `isAdmin` or check the JWT for `ROLE_ADMIN`/`ROLE_MANAGER`

### Step 6: Update the gateway (if needed)

The gateway already forwards `/api/delivery/**` to the delivery service. No changes needed for routing.

The `PUT` method is already proxied since the gateway route doesn't filter by HTTP method.


---

## Files Summary

| Action | File |
|--------|------|
| **NEW** | `delivery/.../security/JwtAuthConverter.java` |
| **EDIT** | `delivery/.../security/DeliverySec.java` |
| **DELETE** | Remove old `jwtAuthenticationConverter()` bean from `DeliverySec.java` |
| **NEW** | `ui-shop/src/pages/DeliveryManagement.tsx` |
| **EDIT** | `ui-shop/src/App.tsx` (add route) |
| **EDIT** | `ui-shop/src/api.ts` (add `updateDeliveryStatus` function) |

---

## Auth check strategy in UI

### Current state

`useAuth()` at `ui-shop/src/auth.tsx:57-58` already decodes JWT roles and exposes `isAdmin`:

```typescript
const roles = (user?.claims?.roles as string[]) ?? [];
const isAdmin = roles.some(r => r === 'ROLE_ADMIN' || r === 'ADMIN');
```

But managers also have `ADMIN` in their roles, so `isAdmin` is `true` for both. No `isManager` exists yet.

### Needed change

Add `isManager` to the `AuthContext` interface and compute it from the JWT's `roles` claim:

```typescript
// auth.tsx
interface AuthContext {
  user: User | null;
  isAuthenticated: boolean;
  isAdmin: boolean;
  isManager: boolean;        // ← new
  logout: () => void;
  loading: boolean;
}
```

```typescript
// auth.tsx — extend the computed values
const roles = (user?.claims?.roles as string[]) ?? [];
const isAdmin = roles.some(r => r === 'ROLE_ADMIN' || r === 'ADMIN');
const isManager = roles.some(r => r === 'MANAGER');  // ← new
```

The `manager` user has bare `MANAGER` in their JWT roles (alongside `ADMIN`). A regular `admin` does not — so this cleanly separates them.

**In `DeliveryManagement.tsx`**: use `const { user, isAdmin, isManager } = useAuth()` and check `if (!isAdmin && !isManager)` to gate access. In the future you can differentiate by checking `isManager` vs `isAdmin` individually.
