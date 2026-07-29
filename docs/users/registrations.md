# Self-service user registration

Plan for letting anonymous visitors create their own account and then log in
with it, plus auto-provisioning a local user record for Google-federated logins.

## Decision: registration belongs to auth-server

`auth-server` is the only service that owns credentials — the `users` /
`authorities` tables, the `PasswordEncoder`, `JpaUserDetailsService` and the
Liquibase schema all live there. `profile` (8064) keys its rows off the JWT
subject and cannot mint a login. Any other placement would mean shipping raw
passwords or password hashes across a service boundary.

`auth-server` is the platform's one servlet/MVC service (Spring Authorization
Server is not reactive), so the registration endpoint is a plain
`@RestController` with JPA — **not** the `RouterFunction` style used by the
WebFlux services. That is expected here.

### Chosen shape

- **JSON API in auth-server + a React `/register` page in ui-shop.**
- **No email verification** — accounts are enabled on creation and can log in
  immediately. The schema is shaped so verification can be layered on later.
- **Google logins auto-provision a local `users` row** on first sign-in, so
  federated users get a real identity and DB-backed roles like everyone else.

### Why no new routing is needed

`auth-server` runs under `server.servlet.context-path: /auth`, so a controller
mapped to `/api/register` is publicly reachable at `/auth/api/register`.

- `gateway/RouterConfig.java:67` already routes `/auth/**` → auth-server.
- `gateway/GateSec.java` is `anyExchange().permitAll()` — no gateway change.
- `ui-shop/nginx.conf` already proxies `location /auth/` → gateway (prod).
- `ui-shop/vite.config.ts` already proxies `/auth` → `localhost:8080` (dev).

So the browser calls `/auth/api/register` **same-origin** in both dev and prod.
No CORS configuration, and no new public surface on the gateway.

> Note the path is under `/auth/api/...`, not `/api/...`. The gateway's `/api/**`
> routes point at the business services; only `/auth/**` reaches auth-server.

---

## Phase 1 — Schema (auth-server)

New changelog `src/main/resources/db/changelog/003-federated-users.sql`,
registered in `db.changelog-master.yaml` after `002-seed-users.sql`.

```sql
--liquibase formatted sql

--changeset moldo:003-users-provider-columns
ALTER TABLE users ADD COLUMN provider    VARCHAR(32)  NOT NULL DEFAULT 'LOCAL';
ALTER TABLE users ADD COLUMN provider_id VARCHAR(255);
CREATE UNIQUE INDEX uk_users_provider_id ON users(provider, provider_id)
    WHERE provider_id IS NOT NULL;
--rollback DROP INDEX uk_users_provider_id; ALTER TABLE users DROP COLUMN provider_id; ALTER TABLE users DROP COLUMN provider;
```

`password` stays `NOT NULL`. Google-provisioned accounts get a generated
unguessable password instead (Phase 4) — see the rationale there.

Verify the existing `users.password VARCHAR(72)` is wide enough: a
delegating-encoder bcrypt value is `{bcrypt}` + 60 chars = 68.  Note this is a limit on
the *stored hash*, not the input — bcrypt output is fixed-width regardless of how
long the generated password is.

### Gotcha to fix while here

`UserEntity` maps `created_at` / `updated_at` as `nullable = false` with no
population strategy. The DB defaults only apply when the column is omitted from
the INSERT — Hibernate includes them explicitly as `NULL`, so the first
programmatic insert will fail the NOT NULL constraint. (The seed users were
inserted by raw SQL, which is why this has never surfaced.)

Fix in `UserEntity`: annotate with Hibernate's `@CreationTimestamp` on
`createdAt` and `@UpdateTimestamp` on `updatedAt`.

## Phase 2 — Registration API (auth-server)

Add `implementation("org.springframework.boot:spring-boot-starter-validation")`
to `auth-server/build.gradle.kts`.

New files under `src/main/java/org/granitesecurity/authserver/user/`:

| File | Purpose |
| --- | --- |
| `RegistrationRequest.java` | Validated record: `username`, `email`, `password`, optional `firstName`/`lastName` |
| `RegistrationResponse.java` | Record returned on 201: `username`, `email` — never the password or id |
| `UserRegistrationService.java` | `@Transactional` create: normalize, check duplicates, encode password, grant `ROLE_USER` + `USER` |
| `DuplicateUserException.java` | Carries which field collided (`username` or `email`) |
| `RegistrationController.java` | `@RestController` on `/api/register` |
| `UserExceptionHandler.java` | `@RestControllerAdvice` → `ProblemDetail` |

**Validation rules** (`RegistrationRequest`):
- `username`: `@NotBlank`, `@Size(3..64)`, `@Pattern("^[a-zA-Z0-9._-]+$")` — 64 is
  the column limit.
- `email`: `@NotBlank`, `@Email`, `@Size(max = 255)`.
- `password`: `@NotBlank`, `@Size(min = 8, max = 72)` — 72 bytes is bcrypt's hard
  input limit; anything beyond it is silently truncated, so reject it explicitly.
- `firstName` / `lastName`: `@Size(max = 64)`.

**Service logic:**
1. Lowercase-normalize username and email before storing and comparing.
2. `existsByUsernameIgnoreCase` / `existsByEmailIgnoreCase` → throw
   `DuplicateUserException` with the offending field.
3. `passwordEncoder.encode(...)` — the existing `PasswordEncoderFactories`
   delegating bean, which produces the `{bcrypt}$2a$10$…` format the seed rows
   already use.
4. `enabled = true`, `provider = LOCAL`, `providerId = null`.
5. Grant both `ROLE_USER` and `USER`, matching the seed users — the JWT `roles`
   claim (`SecurityConfig.jwtTokenCustomizer`) copies authorities verbatim, and
   ui-shop's `isAdmin` check reads that claim.
6. Rely on the DB unique constraints as the real guard: catch
   `DataIntegrityViolationException` and re-map it to `DuplicateUserException`,
   so two concurrent signups for the same username can't both win the
   check-then-insert race.

**Error contract** — return RFC 7807 `ProblemDetail`, which `ui-shop`'s
`api/client.ts` already understands (it reads `data.detail ?? data.title`):
- `400` — validation failure; body includes a `errors` map of field → message.
- `409` — `DuplicateUserException`; `detail` names the conflicting field.
- `201` — created, body `RegistrationResponse`.

## Phase 3 — Security config (auth-server)

In `SecurityConfig.defaultSecurityFilterChain` (`@Order(2)`), which today is
`anyRequest().authenticated()`:

```java
.authorizeHttpRequests(authorize -> authorize
        .requestMatchers(HttpMethod.POST, "/api/register").permitAll()
        .requestMatchers("/error").permitAll()
        .anyRequest().authenticated())
.csrf(csrf -> csrf.ignoringRequestMatchers("/api/register"))
```

**Both lines are required.** Spring Security enables CSRF by default in servlet
apps, so without the `ignoringRequestMatchers` the SPA's JSON POST is rejected
with a 403 before it ever reaches the controller — this is the single most
likely thing to go wrong in this feature. It is safe to exempt this one endpoint:
it is unauthenticated, creates a brand-new principal, and has no session or
ambient authority for a forged request to ride on.

Note the matcher path is `/api/register` (context-path relative), not
`/auth/api/register`.

## Phase 4 — Google auto-provisioning (auth-server)

Today `oauth2Login` authenticates against Google and maps authorities via
`oAuth2LoginAuthoritiesMapper`, but writes nothing to the DB — those users have
no local row, no DB-backed roles, and nothing for `profile` to key off
consistently.

Add `FederatedUserProvisioningService` and register a custom `OidcUserService`
on the `oauth2Login(...).userInfoEndpoint(...)` chain that, after delegating to
the default service, runs inside a transaction:

1. Read `sub`, `email`, `email_verified`, `given_name`, `family_name`.
2. **Require `email_verified == true`.** Reject the login otherwise — the
   email-based linking in step 4 is only safe because Google attests the address.
3. Look up by `(provider = GOOGLE, provider_id = sub)` → if found, refresh
   profile fields and return.
4. Otherwise look up by email:
   - existing `LOCAL` user with that email → **link**: set `provider_id`, keep
     `provider = LOCAL` so the password still works, and both login methods
     resolve to one account.
   - no match → create a new row: `provider = GOOGLE`, `enabled = true`, a
     generated password (below), username derived from the email local-part with
     a numeric suffix on collision, granted `ROLE_USER` + `USER`.
5. Return an `OidcUser` whose authorities come from the DB row (plus the
   existing `FactorGrantedAuthority` the current mapper adds), so the `roles`
   claim in the issued JWT is identical in shape to a form login's.
6. **Publish `UserRegistered` — but only for step 4's "no match" branch.**
   *(Added later; see below.)*

### Step 6, added later: the federated path announces itself

As originally built, only `UserRegistrationService` published `UserRegistered`.
The federated path wrote its `users` row and told nobody, so for every
Google-provisioned account:

- **`profile` never learned their email or name.** Its row is created lazily on
  first visit to "My Profile" as a username-only stub, and the consumer that
  fills in the blanks (`UserRegisteredConsumer`) never fired for them. The email
  was not lost — it lives in `authdb.users.email`, which is why the admin list
  still shows it correctly (that list is built from auth users, not profiles —
  `blocking-users.md` D3) — but anything reading `profiledb` saw a blank.
- **`notification` never sent them a welcome mail**, since it consumes the same
  event.

`FederatedUserProvisioningService` now publishes it, with the same payload and
the same `afterCommit` discipline as the form path. Which branch publishes is
the whole design:

| Branch | Publishes? | Why |
|---|---|---|
| found by `(GOOGLE, sub)` | no | a returning user; this runs on **every** login |
| found by email → link | no | already has a profile and already got a welcome mail at registration; nothing was registered, a second login method was attached |
| no match → create | **yes** | this, and only this, is a new account |

**This means Google sign-ups now get a welcome email where they previously got
none.** That is the intended behaviour — it is their account being created — but
it is a user-visible change, not just plumbing.

Reuse the same authority-granting helper as `UserRegistrationService` so local
and federated users can never drift apart.

**Guard `JpaUserDetailsService`:** if the loaded row has a `null` password,
throw `UsernameNotFoundException`. A Google-only account must not be reachable
through the form-login path.

## Phase 5 — Register page (ui-shop)

| File | Change |
| --- | --- |
| `src/api/auth.ts` | New. `authApi.register(payload)` → `request('/auth/api/register', { method: 'POST', skipAuth: true, body: … })` |
| `src/api.ts` | Add `auth: authApi` to the `api` barrel |
| `src/types.ts` | `RegistrationRequest` / `RegistrationResponse` types |
| `src/pages/Register.tsx` | New page |
| `src/App.tsx` | `<Route path="register" element={<Register />} />` |
| `src/components/Header.tsx` | Next to the existing `Login` link (line 27), add a `Register` link in the same anonymous-only branch |

`skipAuth: true` matters — `client.ts` otherwise attaches a stale `Bearer` token
if one is in memory, and `client.ts` throws a bare `Unauthorized` on any 401.

`Register.tsx` behaviour:
- Fields: username, email, password, confirm password, optional first/last name.
- Client-side mirror of the server rules (length, pattern, passwords match) for
  fast feedback — the server remains the authority.
- On `409`, surface the message against the specific field the `ProblemDetail`
  names.
- On success, show a brief confirmation and then call
  `userManager.signinRedirect()` (the same call `pages/Login.tsx` makes) so the
  user lands on the auth-server login form and signs in with the credentials
  they just chose.
- If already authenticated, redirect to `/` — mirroring `Login.tsx`.

### Why not auto-login after registering

The SPA holds no auth-server session, and minting one from the registration
endpoint would mean issuing tokens outside the authorization-code flow. Bouncing
through `signinRedirect()` keeps the single OIDC path and costs one form fill.

## Phase 6 — Tests

- `UserRegistrationServiceTest` — mocked repository: normalization, duplicate
  detection on both fields, `ROLE_USER` + `USER` granted, password is encoded and
  not stored raw, `DataIntegrityViolationException` maps to 409.
- `RegistrationControllerTest` — `@WebMvcTest` + `MockMvc`: 201 on valid input,
  400 with field errors, 409 on duplicate, and **an explicit test that the
  endpoint is reachable unauthenticated with no CSRF token** (the Phase 3 trap).
- `JpaUserDetailsServiceTest` — a null-password row is not loadable.
- `FederatedUserProvisioningServiceTest` — new user created; repeat login is
  idempotent; existing-email link path; `email_verified = false` rejected;
  username collision gets a suffix.
- Repository/Liquibase test with Testcontainers — asserts changelog 003 applies
  and that a programmatic insert populates `created_at` / `updated_at` (the
  Phase 1 gotcha).

## Phase 7 — Manual verification

1. `docker compose up` (auth-server + gateway + postgres), `npm run dev` in
   `ui-shop`.
2. `POST http://localhost:8080/auth/api/register` via curl → expect 201.
3. Re-POST the same body → expect 409.
4. Browser: `/register`, sign up, get redirected to the auth-server login form,
   log in with the new credentials, land back on the SPA authenticated.
5. Decode the access token and confirm the `roles` claim contains `ROLE_USER`.
6. Confirm the new user can place an order end-to-end (shop → payment).
7. Google sign-in with a fresh Google account → confirm exactly one new `users`
   row with `provider = GOOGLE`, and that signing in a second time creates no
   duplicate.

## Deployment notes

- No new env vars, no new secrets, no gateway/nginx/k8s manifest changes.
- Changelog 003 runs automatically on auth-server startup (Liquibase).
- CI only rebuilds changed service directories, so a commit touching both
  `auth-server/` and `ui-shop/` builds and pushes both images. Remember that
  `:latest` does not auto-restart pods —
  `kubectl -n granite rollout restart deployment auth-server ui-shop`.

## Accepted risks / follow-ups

- **No rate limiting.** The endpoint is open to automated signup abuse. The
  gateway has no rate-limit filter configured and Spring Cloud Gateway's
  `RequestRateLimiter` needs Redis, which the platform does not run. Worth doing
  as a follow-up; out of scope here.
- **Username enumeration.** Returning "username already taken" is inherent to a
  usable signup form. Accepted deliberately.
- **No email verification**, so addresses are unverified for `LOCAL` accounts —
  do not treat `users.email` as proof of ownership for anything security-relevant
  until Phase "verification" lands. Google-provisioned addresses *are* verified
  (Phase 4 step 2).
- **RSA keys are still regenerated on every auth-server restart**
  (`SecurityConfig.jwkSource`), so all sessions break on redeploy. Unrelated to
  registration, but new users will hit it — worth a separate ticket.
