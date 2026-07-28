# `notification` microservice — extraction & event-driven refactor

Status: **Phases 1–4 implemented** (identity events end-to-end; commerce notifications and Phase 5 cleanup outstanding)
Author: design note, 2026-07-28

## 1. Why

Today the email path is:

```
auth-server (servlet, JPA)
  PasswordChangeService / PasswordResetService
    → afterCommit → ProfileNotificationClient (@Async, blocking RestClient, client-credentials JWT, scope=internal)
      → POST profile:8064 /api/profiles/internal/{username}/notify/password-changed
      → POST profile:8064 /api/profiles/internal/{username}/notify/password-reset-requested
        → InternalNotificationHandler → EmailService → ResendClient → api.resend.com
```

Problems with that arrangement:

- **Wrong owner.** `profile` owns user profiles and delivery addresses. Owning HTML email templates, a Resend API key, and a "send transactional mail" capability is unrelated to its bounded context. `EmailTemplates` / `EmailService` / `ResendClient` currently live in `profile/notification/` only because profile happened to know users' email addresses.
- **Point-to-point coupling.** auth-server needs a client-credentials `RegisteredClient`, a `profile-client` registration, a `MICROSERVICES_PROFILE_URI`, and its own swallow-all-failures error handling — all to hand off a message. Every future sender would need the same.
- **No fan-out.** `shop` (order confirmation), `delivery` ("your parcel shipped"), `payment` (receipt) would each need to repeat that setup.
- **Single channel.** SMS (Twilio), WhatsApp, or push can't be added without every caller learning about channels.

### Verdict

Yes to both halves of the idea, and they are independent:

1. **Extract a `notification` service.** Clearly correct. Removes an unrelated responsibility (and the Resend secret) from `profile`, and gives one home for templates, channel adapters, provider retries, suppression lists, and a delivery audit log.
2. **Publish to Kafka instead of calling over HTTP.** Correct. The sender stops knowing who delivers, and consumers can be added without touching producers. It also matches the topic conventions already in `docs/events/events.md`.

### Naming

**`notification`**, singular — matching the repo's plain singular domain nouns (`shop`, `payment`, `delivery`, `profile`, `storage`; `greetings` is the outlier).

| Thing | Value |
|---|---|
| Directory | `notification/` |
| Port | **8066** (8060–8065 taken: greetings, shop, payment, delivery, profile, storage) |
| Image | `moldovean/granite-notification` |
| Database | `notificationdb` |
| Kafka topic | `identity.events` — named for the **producing domain**, matching `orders.events` / `payments.events` in `docs/events/events.md`. Originally `notifications.events`; renamed once `profile` became a second consumer, since a topic named for one consumer is a misnomer the moment there are two. |
| Consumer groups | `notification.identity.events.consumer`, `profile.identity.events.consumer` |

## 2. Delivery guarantees — decided

**auth-server does NOT get the outbox pattern.** Deliberate, and a departure from `shop` / `payment` / `delivery`.

Rationale:

- The three auth-server messages are password-changed (courtesy), welcome (courtesy), and password-reset-requested. Losing either courtesy message is invisible to the user.
- Losing a **password-reset** message is user-visible, but the retry is free and user-driven: click "forgot password" again and a fresh token is issued. Self-service, not a support ticket.
- An outbox in auth-server would mean a new Liquibase changelog, a JPA entity + repository, `@EnableScheduling`, and a polling relay thread — real infrastructure in the one service that is servlet/JPA and has none of it today. Not worth it for at-most-once-acceptable mail.

So: **fire-and-forget produce, at-most-once, message loss accepted.** The reactive services keep their existing outboxes — nothing changes there, and `notification` simply subscribes to the topics they already publish.

### How the produce must be written

Do **not** naively `kafkaTemplate.send(...).get()` on the request thread. The producer's default `max.block.ms` is 60s, so a dead broker would hang the password-change HTTP response for a minute — strictly worse than the current HTTP call.

Keep the existing structure, which already has the right shape, and swap only the transport:

- Keep **`@Async`** — the send stays off the request thread.
- Keep the **`afterCommit`** transaction synchronization — a rolled-back password change must not emit an event. (With an outbox this guarantee would come from the DB; without one, the callback is still doing the job it does today.)
- Keep the **catch-and-log** — a broker hiccup must never surface to the user.
- Set **`max.block.ms=2000`** and a short `delivery.timeout.ms` on the producer, so a broker outage fails fast instead of pinning an async worker.

The result is a rename plus a transport swap on `ProfileNotificationClient` → `NotificationEventPublisher`, roughly a 20-line diff. No new table, no scheduler, no relay.

### Consumer side still needs dedupe

Independent of how the producer writes: Kafka consumers are at-least-once, and a rebalance or a redelivered offset will re-present a message. Sending a password-reset email twice is user-visible, so `notification` keeps a `processed_events` table and inserts before sending. At-most-once produce + effectively-once consume.

## 3. Target architecture

```
auth-server ──→ fire-and-forget produce ──→ Kafka `identity.events`
                                                     ↓
shop / payment / delivery ──→ (existing outboxes, unchanged) ──→ orders.events
                                                                 payments.events
                                                                 delivery.events
                                                     ↓
                                        notification:8066 (WebFlux + R2DBC)
                                           ├─ consumers (idempotent, dedupe by event id)
                                           ├─ recipient resolution + preferences
                                           ├─ template registry (type × channel × locale)
                                           ├─ channels: Email (Resend) → SMS (Twilio) → WhatsApp → push
                                           └─ notification_log (audit: what, to whom, provider id, status)
```

- Own Postgres DB `notificationdb`, Liquibase-managed, R2DBC — same shape as shop/payment/delivery/profile.
- WebFlux + functional routing (`RouterFunction` + handler classes), matching `greetings` as the reference implementation.
- **OAuth2 client, not a resource server** — see below.

### Security posture — client, not resource server

Unlike every other service here, `notification` is **not** an OAuth2 resource server in Phases 1–5.

**Why not.** It has no inbound API to protect. It is a pure Kafka consumer: messages arrive over the broker, not over HTTP. The only HTTP it serves is `/actuator/health` for k8s probes — and actuator is already unauthenticated on the existing services (`ProfileSec` ends with `.anyExchange().permitAll()`). A `SecurityWebFilterChain` guarding zero endpoints is worse than none: it reads as "this service is secured" while securing nothing, and the `jwt.trusted-issuers` / `jwk-set-uri` config it drags along becomes stale config nobody exercises.

It is also not exposed: **no gateway route, no `HTTPRoute`, no Ingress**. It is reachable only cluster-internally, and nothing calls it.

**What it does need — the opposite role.** To resolve recipients for the commerce events it calls profile's `/api/profiles/internal/{username}`, which requires `SCOPE_internal` (`profile/.../ProfileSec.java:55`). So it needs to *obtain* a token, not validate one:

- dependency `spring-boot-starter-oauth2-client` (**not** `-oauth2-resource-server`)
- a `client_credentials` `ReactiveOAuth2AuthorizedClientManager` + a `WebClient` with `ServerOAuth2AuthorizedClientExchangeFilterFunction`, mirroring `profile/.../InternalClientConfig.java`, which already does exactly this to reach storage
- a `notification-client` `RegisteredClient` with scope `internal`, registered in auth-server's `SecurityConfig`

Only the outbound calls are authenticated; nothing inbound is.

**When it becomes a resource server:** Phase 6, with the in-app inbox (`GET /api/notifications`). That is the first genuinely user-facing endpoint, and it arrives together with a gateway route, CORS, and a `NotificationSec` copied from `ProfileSec`. Adding it then is a contained change; adding it now is config guarding nothing.

### Events

**Topic `identity.events`** — auth-server identity facts. Consumed by `notification` (email) and `profile` (profile provisioning):

| Event type | Payload |
|---|---|
| `PasswordChanged` | `username`, `email`, `changedAt` |
| `PasswordResetRequested` | `username`, `email`, `resetToken`, `expiresAt` |
| `UserRegistered` | `username`, `email`, `firstName`, `lastName`, `occurredAt` |

Key = `username`, so all messages for one user stay ordered in one partition. `partitions=3`, `replication.factor=1` in dev.

**Existing topics** `orders.events` / `payments.events` / `delivery.events` — `notification` subscribes with its own consumer group. **Zero producer-side change**; do not ask shop/payment/delivery to double-publish. This keeps the blast radius of the refactor on auth-server alone.

Payload format: JSON, matching what `EventConsumer` / `PaymentEventConsumer` already parse. The Avro envelope in `docs/events/events.md` is aspirational and not yet implemented in code; migrating to it is a separate concern.

### Recipient resolution

- **Auth events:** carry `email` in the payload. auth-server already has it on `UserEntity` and already passes it in the HTTP body today. No runtime dependency.
- **Commerce events:** carry `username` only, so `notification` looks the address up via profile's existing `/api/profiles/internal/{username}` route. A synchronous dependency, but from a consumer where retry is free (redeliver the message) rather than from a request path.

A small `RecipientResolver` prefers the event-carried address and falls back to the profile lookup — a direct generalisation of `InternalNotificationHandler.resolveEmail`, which already does exactly this.

### Content ownership — who writes the email text

**`notification` owns 100% of the copy. Producers publish structured data and never any rendered text.**

The contract between a producer and `notification` is the **event schema**, not the message body. `auth-server` publishes *"the password for user X changed at time T"*. It does not know that an email exists, what the subject line is, or that there is any HTML involved.

```
auth-server ──→ { "type": "PasswordChanged",           notification ──→ TemplateRegistry
                  "username": "ada",                                     (eventType × channel × locale)
                  "email": "ada@example.com",                              ↓
                  "occurredAt": "2026-07-28T10:15:00Z" }            subject / html / text
                                                                           ↓
                  ^ data only — no subject, no body                  EmailChannel → Resend
```

#### Why not let producers send the body

The obvious alternative — producer renders the text, `notification` is a dumb send-pipe — fails on the multi-channel goal that motivated this whole design:

- **A rendered body has already picked a channel.** An HTML email body cannot become a 160-character SMS or a WhatsApp template message. Rendering must happen *after* the channel is chosen, and only `notification` knows the channel (it owns the preferences table). Ship bodies and Phase 6 is unreachable.
- **Every producer would need templating.** HTML escaping, branding, layout, a text/plain alternative — duplicated in `auth-server`, `shop`, `payment`, `delivery`, and re-solved by every future producer.
- **Copy changes would become multi-service deploys.** Fixing a typo or updating branding would touch every producer instead of one directory.
- **Localisation would be impossible.** The producer does not know the recipient's locale; `notification` does, after recipient resolution.
- **Escaping bugs would multiply.** The `escapeHtml` helper in today's `EmailTemplates` is load-bearing — it escapes the reset link. That safety property should exist in exactly one place.

#### Events are facts, not commands

Name events for what happened, not for what should be sent:

| ✅ Domain fact | ❌ Command |
|---|---|
| `PasswordChanged` | `SendPasswordChangedEmail` |
| `OrderPlaced` | `SendOrderConfirmation` |

A command-shaped event puts the channel decision back in the producer, just spelled differently. Facts keep routing where the preferences live. (This also means `notification` can decide to send *nothing* — e.g. the user disabled that notification type — without the producer ever knowing.)

#### "But the content genuinely comes from the producer"

Order confirmations list line items; that content really does originate in `shop`. It is still **data**: the event carries `items[]` with product names, quantities and prices, and the template renders the HTML table. Markup never crosses the bus.

The one deliberate escape hatch is a `GenericNotification` event type carrying a caller-supplied subject/body, for admin or operational one-offs. Keep it rare and explicitly marked — it bypasses every property above, and if it becomes routine the design has quietly degraded into the dumb-pipe model.

#### Where the copy physically lives

Move the copy out of Java text blocks (today's `EmailTemplates.java`) into resource files, one set per event type × channel:

```
notification/src/main/resources/templates/
  email/
    password-changed.subject.mustache
    password-changed.html.mustache
    password-changed.txt.mustache        # text/plain alternative — Resend takes both
    password-reset-requested.{subject,html,txt}.mustache
    user-registered.{subject,html,txt}.mustache
  sms/                                    # Phase 6
    password-changed.txt.mustache
  _layout/
    base.html.mustache                    # shared header/footer/branding
```

**Mustache** (`spring-boot-starter-mustache`), not Thymeleaf:

- **Logic-less by design** — business logic cannot leak into a template, which is exactly the constraint you want for copy.
- **`{{ }}` auto-escapes HTML**, `{{{ }}}` opts out. This replaces the hand-rolled `escapeHtml` helper with a safe default, and the reset link stays escaped without anyone remembering to do it.
- Tiny, no Spring MVC coupling, renders fine off a WebFlux worker (rendering is CPU-only, no I/O).

Thymeleaf would also work but is heavier and its natural-templating features buy nothing for email.

**Template contract:** templates must tolerate missing optional fields (Mustache renders an absent key as empty rather than failing), so adding a field to an event is backward-compatible and old producers keep working during a rolling deploy. A missing *required* field should fail loudly at render time and be logged to `notification_log`, not sent half-rendered.

**Practical payoff:** changing wording is a one-line edit to a `.mustache` file in a single service — no producer redeploy, no schema change, no Java recompile upstream. Copy review can happen against a directory of plain-text files.

## 4. Decisions

| # | Decision | Resolution |
|---|---|---|
| D1 | Outbox in auth-server? | **No.** Fire-and-forget, at-most-once, loss accepted (§2). |
| D2 | Reset link on Kafka? | Publish the **raw token**, not the full link. The token already expires in 30 min and is single-use, so exposure is bounded and no worse than it sitting in an inbox. Retention and staleness are covered in §4.1 — they are two different mechanisms and both are needed. |
| D3 | Who renders the link URL? | `notification`, from a configured `frontendOrigin`. Removes `FRONTEND_ORIGIN` duplication and lets the link format change without touching auth-server. |
| D4 | `kafka-ui` with PII on the bus? | Deploy it to k8s (it's genuinely useful), but **cluster-internal only, reached via `kubectl port-forward` — no public hostname**. See §6.1. |
| D5 | Keep profile's HTTP endpoints during migration? | Yes — dual-write in Phase 3, delete in Phase 5. Never a flag day. |
| D6 | Consumer dedupe table in Phase 1? | Yes. At-least-once consumption means duplicates are guaranteed, not hypothetical (§2). |
| D7 | Event payload format | JSON. Avro/Schema-Registry is out of scope. |

### 4.1 Retention vs. staleness

These solve different problems and neither substitutes for the other.

#### Retention — how long the message sits in the log

Nothing in this repo currently configures retention. Neither `compose.yaml` nor `k8s/base/kafka.yaml` sets any `KAFKA_LOG_RETENTION_*`, and topics are auto-created, so **every existing topic inherits the broker default of 7 days**. (`docs/events/events.md` documents 7 days for `orders.events` et al. — that happens to be true, but by default, not by configuration.)

For `identity.events`, create the topic **explicitly** with:

| Config | Value | Why |
|---|---|---|
| `retention.ms` | `3600000` (1 h) | Shortest of any topic here; these messages are worthless minutes after they're consumed, and one carries a reset token |
| `segment.ms` | `600000` (10 min) | **Required, not optional** — see below |
| `cleanup.policy` | `delete` | Not compacted; there is no "latest state per key" to keep |

**The `segment.ms` trap:** `retention.ms` only makes *closed* segments eligible for deletion — the active segment is never deleted. Defaults are `segment.ms=7 days` and `segment.bytes=1GB`. This topic will carry a handful of messages a day, so the active segment would stay open for a week and a 1-hour `retention.ms` would delete **nothing** for up to 7 days. On a low-volume topic, `retention.ms` without a matching `segment.ms` is a no-op. Setting `segment.ms=600000` bounds actual deletion to roughly `retention.ms + segment.ms` ≈ 70 minutes worst case.

Create it via a `NewTopic` bean in `notification` (so the config is versioned in code and applied on every environment) rather than relying on auto-creation:

```java
@Bean
NewTopic notificationsEvents() {
    return TopicBuilder.name("identity.events")
            .partitions(3)
            .replicas(1)
            .config(TopicConfig.RETENTION_MS_CONFIG, "3600000")
            .config(TopicConfig.SEGMENT_MS_CONFIG, "600000")
            .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
            .build();
}
```

Note `KafkaAdmin` only applies these at **creation**; it does not alter an existing topic. If the topic was already auto-created during development, delete it or `kafka-configs --alter` it once.

#### Staleness — whether we still act on the message

Retention does **not** give per-message TTL, and this is the part that actually matters for correctness. A consumer that was down for 50 minutes restarts and processes a 50-minute-old `PasswordResetRequested`: the message is still well inside retention, so Kafka serves it, and the user gets an unprompted "reset your password" email carrying an already-expired token. Deleting messages from the log does nothing to prevent a consumer from processing one that is still there.

So `notification` applies its own freshness check at consume time, before rendering:

| Event | Max age | On expiry |
|---|---|---|
| `PasswordResetRequested` | 5 min, **and** `expiresAt` must be in the future | drop |
| `PasswordChanged` | 1 h | drop |
| `UserRegistered` | 24 h | drop (a late welcome mail is odd but harmless — generous window) |
| commerce events | 24 h | drop |

Dropped events are **recorded in `notification_log` with status `DROPPED_STALE` and the offset committed** — never retried, never dead-lettered. A stale event is not a failure; retrying it would just send the wrong email later, and failing it would stall the partition.

This check also needs `occurredAt` in every payload — already listed in the §3 event table.

## 5. Phased plan

Each phase is independently shippable and leaves the system working. Phases 1–2 add the new path without touching the old one; Phase 3 runs both; Phase 4 flips the sender; Phase 5 deletes.

---

### Phase 1 — Scaffold `notification` (no behaviour change) — ✅ DONE

Goal: a running, deployable, empty-but-healthy service.

**Deviations from the plan as written, decided during implementation:**

1. **No `spring-boot-starter-oauth2-client` yet — deferred to Phase 4.** Two reasons. It has no user until Phase 4's `RecipientResolver` calls profile; and more importantly, pulling Spring Security onto the classpath *without* a `SecurityWebFilterChain` bean triggers Boot's default lockdown, which would require authentication on every endpoint including anything used for probes. Adding the starter therefore also forces a permit-all chain to be written and maintained — config that guards nothing, for a capability nothing uses yet. `InternalClientConfig` and the auth-server `notification-client` `RegisteredClient` move to Phase 4 with it. **Phase 1 touches no existing service's code at all** — only infra files.
2. **No actuator, no health probes.** The plan's "done when `/actuator/health` responds" does not match this repo: no service has the actuator dependency and `k8s/base/*.yaml` defines no `livenessProbe`/`readinessProbe` anywhere. Matched the existing convention rather than making `notification` the odd one out. Adding actuator across all services is a reasonable separate change, but not this one's job.
3. **The `NewTopic` bean shipped in Phase 1, not Phase 3.** Strictly safer: if anything auto-creates `identity.events` first, it gets the broker's 7-day default and `KafkaAdmin` will *not* retroactively fix it (§4.1). Creating it before any producer exists removes that race.
4. **Kafka consumer settings live in `application.yaml`**, not a copied `KafkaConfig` class. Boot's auto-configuration covers what `shop/config/KafkaConfig.java` does by hand, so the only Java config is the topic bean.
5. Uses the Boot 4 starter `spring-boot-starter-kafka` rather than shop's `org.springframework.kafka:spring-kafka` coordinate.

**Verified:** `./gradlew build -x test` succeeds; the service starts on 8066; Liquibase runs both changesets against `notificationdb`; `kafka-topics --describe` reports `identity.events` with `retention.ms=3600000, segment.ms=600000, cleanup.policy=delete` across 3 partitions. `kubectl kustomize k8s/base` and `docker compose config` both render.

1. `notification/` directory: Gradle wrapper, `build.gradle.kts` based on `profile` (WebFlux, R2DBC, Postgres driver, Liquibase, `spring-boot-starter-kafka`, Lombok). **No OAuth2 starter of either kind** (§3 "Security posture" + deviation 1).
2. `application.yaml`: `server.port: ${NOTIFICATION_SERVER_PORT:8066}`, R2DBC + JDBC (Liquibase) URLs for `notificationdb`, `spring.kafka.*` consumer settings, and the `resend.*` block copied from `profile/src/main/resources/application.yaml:52-55` (left blank so `EmailService` will log-and-skip until Phase 2). **No `jwt.trusted-issuers` / `jwk-set-uri`** — nothing inbound is validated.
3. Liquibase changelog `001-create-notification-schema.sql`:
   - `processed_events (event_id uuid pk, event_type text, processed_at timestamptz)` — consumer dedupe.
   - `notification_log (id uuid pk, event_id uuid, type text, channel text, recipient text, status text, provider_message_id text, error text, created_at timestamptz)` — audit, and the thing that makes "did the user get the email?" answerable.
4. `KafkaTopicConfig` — the `NewTopic` bean from §4.1. Consumer settings go in `application.yaml` (Boot auto-config). **No `NotificationSec`, no `RouterFunction`, no OAuth2 client config** — there is no inbound API and no outbound call yet; both arrive in Phase 4 (see deviations above).
   - No gateway route and no k8s `HTTPRoute` for this service, in any overlay.
5. `Dockerfile` mirroring `profile/Dockerfile`.
6. Infra:
   - `compose.yaml`: `notification-postgres` + `notification` services, depending on kafka and its DB.
   - `k8s/base/notification.yaml` (Deployment + Service, modelled on `k8s/base/profile.yaml`), added to `k8s/base/kustomization.yaml`; add the Postgres instance to `k8s/base/postgres.yaml`.
   - CI (`.github/workflows/ci.yml`) diffs changed directories, so it should pick the service up automatically — confirm the matrix doesn't hardcode a service list.
   - `k8s/base/kafka-ui.yaml`: Deployment + Service for `kafbat/kafka-ui`, pointed at `kafka:29092`. **No `HTTPRoute` in any overlay** (§6.1) — reached via `kubectl -n granite port-forward deploy/kafka-ui 8090:8080`. Clears the pending item in `k8s/todo.md` and makes Phases 3–5 far easier to observe.

**Done when:** the service starts against `notificationdb`, Liquibase creates both tables, and `identity.events` exists with the right retention — all confirmed above.

---

### Phase 2 — Move the email capability into `notification` — ✅ DONE

Goal: the new service can send email; profile still does too. No producer changes yet.

1. Copy (delete from profile in Phase 5) `EmailService`, `EmailTemplates`, `ResendClient`, `ResendEmailResponse`, `NotificationConfig` from `profile/notification/` into `notification/channel/email/`. Behaviour unchanged: 5s timeout, one retry on 5xx/timeout, disabled when `RESEND_API_KEY` is unset, failures logged not thrown.
2. Introduce the channel abstraction now, so Twilio/WhatsApp later is an addition rather than a refactor:
   ```java
   public interface NotificationChannel {
       Channel channel();                                   // EMAIL, SMS, WHATSAPP, PUSH
       Mono<DeliveryResult> send(RenderedMessage message);   // provider id or failure
   }
   ```
   with `EmailChannel` (Resend) as the only implementation and a `ChannelRegistry` selecting by `Channel`.
3. `TemplateRegistry`, keyed by `(eventType, channel, locale)`, returning `subject` / `html` / `text`. Add `spring-boot-starter-mustache` and port the two existing templates from `EmailTemplates.java` text blocks into `resources/templates/email/*.mustache` per §3 "Content ownership". The hand-rolled `escapeHtml` helper is **dropped** — Mustache's `{{ }}` auto-escaping replaces it, which is what keeps the reset link safe. Verify that specifically in a test.
4. `NotificationService`: resolve recipient → check preferences (stub: everyone gets email) → render → send via channel → write `notification_log`.
5. Move `profile/.../EmailServiceTest.java` across as-is (it already exists; no new test scaffolding beyond it).
6. Move the secret: `RESEND_API_KEY` / `RESEND_FROM` env from the `profile` deployment to the `notification` deployment in `compose.yaml` and `k8s/base/*.yaml` (keep on both until Phase 5). `k8s/base/secrets.yaml.example` already has `resend-api-key`; it is now consumed by `notification`.

**No test endpoint.** An earlier draft proposed a `@Profile("dev")` `POST /api/notifications/test` to prove the Resend wiring. Dropped: it would be an unauthenticated endpoint (there is no resource server, §3) existing solely to be tested, and a dev-only profile is a second config path to maintain and a real risk of leaking into an overlay. Phase 2 has no runtime trigger of its own — the first real send happens in Phase 4, verified per §6.

**Deviations:**

1. **`EmailService` did not survive as a class.** Its two responsibilities split: the enabled-check and Resend call became `EmailChannel implements NotificationChannel`, and the copy moved to Mustache files. `ResendClient` moved across verbatim (same 5s timeout, same single retry on 5xx/timeout, same retryable predicate).
2. **`EmailServiceTest` was ported, not moved verbatim** — the plan called for moving it as-is, but the API it tested no longer exists. All six cases survive as `EmailChannelTest` against the new interface, plus a case for the empty-recipient path.
3. **Added `TemplateRegistryTest`** despite the general no-new-tests stance, covering exactly the property §6.5 flags as unverifiable by hand: `{{ }}` escapes hostile input in the HTML body, and does *not* escape the plain-text body (escaping there would show the reader a literal `&amp;`). That asymmetry is real and easy to get wrong — the old `EmailTemplates` escaped only in the HTML variants.
4. **Templates are keyed by `(eventType, channel)`, not `(eventType, channel, locale)`.** There is one locale. An always-`"en"` parameter threaded through every call is speculative generality; add it with the second locale.
5. **`TemplateRegistry` resolves the file name from the event type** (`PasswordResetRequested` → `password-reset-requested`), so a new event type needs template files but no registry change. A type with no templates logs a warning and sends nothing rather than failing.
6. `com.samskivert:jmustache` directly rather than `spring-boot-starter-mustache` — we render emails, not web views, so the starter's `MustacheViewResolver` auto-config would be dead weight. Version still managed by the Boot BOM.
7. **Deleted `NotificationApplicationTests`** (the Initializr `contextLoads` stub) — `@SpringBootTest` needs a live Postgres and broker, so it fails outside Docker and adds nothing.

**Done when:** ✅ 9 tests pass; the service starts and renders all three templates.

---

### Phase 3 — auth-server produces to Kafka (dual-write) — ✅ DONE

Goal: auth-server publishes events *in addition to* calling profile over HTTP. Small, reversible.

1. Add `spring-kafka` to auth-server. Producer config: `acks=all`, `enable.idempotence=true`, **`max.block.ms=2000`**, short `delivery.timeout.ms`. `KafkaTemplate<String, String>`, String serdes — same as `shop/config/KafkaConfig.java` minus the consumer half. No `@EnableScheduling`, no outbox table, no relay.
2. Rename `ProfileNotificationClient` → `NotificationEventPublisher` and swap the transport: `RestClient.post()...` becomes `kafkaTemplate.send("identity.events", username, payloadJson)`. **Keep `@Async`, keep the try/catch-and-log** — the failure semantics stay exactly as they are today (§2).
3. Guard on a null/absent `KafkaTemplate` like the existing relays do, so auth-server still boots without a broker.
4. Call sites keep their `afterCommit` transaction synchronizations unchanged:
   - `PasswordChangeService.changePassword` → `PasswordChanged`
   - `PasswordResetService.requestReset` → `PasswordResetRequested`
   - `PasswordResetService.confirmReset` → `PasswordChanged`
   - `UserRegistrationService` → `UserRegistered` (new capability — no email was sent here before)
5. Create `identity.events` explicitly via the `NewTopic` bean in §4.1 — `retention.ms=1h` **and** `segment.ms=10min`. Do not let it auto-create; auto-creation silently gives it the broker default of 7 days.
6. Keep the `ProfileNotificationClient` HTTP calls in place alongside. Both paths fire; only the HTTP one actually sends mail so far.

**Deviation:** none of substance. `ProfileNotificationClient` was **kept** rather than renamed — the new `NotificationEventPublisher` sits alongside it, since Phase 3 is explicitly a dual-write and Phase 5 deletes the old class outright. Renaming a class that is about to be deleted would only churn the diff.

**Verified end-to-end:** `POST /auth/api/register` returned 201 and auth-server logged `published UserRegistered for e2euser` **on thread `task-1`** — confirming the send stayed off the request thread. `POST /auth/api/password-reset/request` likewise published `PasswordResetRequested`.

A useful accident during verification: profile was not running, so the old HTTP path failed with `failed to notify profile of password reset request: I/O error` — logged, swallowed, and invisible to the caller — while the Kafka path succeeded and the notification was delivered. That is precisely the durability improvement this refactor buys, demonstrated live.

---

### Phase 4 — `notification` consumes; flip the sender — ✅ DONE (identity events; commerce deferred)

1. `IdentityEventConsumer` — `@KafkaListener(topics = "identity.events", groupId = "notification.identity.events.consumer")`. Parse JSON, dedupe on event id against `processed_events`, dispatch to `NotificationService`, record the outcome. Modelled on `delivery/consumer/PaymentEventConsumer.java` for the parse/error idiom, but with the dedupe insert as the commit gate.
2. Idempotency contract: insert into `processed_events` **before** sending, in the transaction that claims the event; a duplicate-key violation means "already handled, skip".
   - Apply the §4.1 staleness check first, before dedupe and rendering: too-old events are logged `DROPPED_STALE`, committed, and never retried.
3. Flip via config, so it reverts without redeploying auth-server:
   - `notification.send.enabled=true` on the new service.
   - Disable profile's sender by **unsetting `RESEND_API_KEY`** on the profile deployment — `EmailService` already log-and-skips when it's blank (`profile/.../EmailService.java:22`). No code change needed.
4. Add consumer groups on the existing commerce topics: `OrderEventConsumer` / `PaymentEventConsumer` / `DeliveryEventConsumer` in `notification`, using `RecipientResolver` → profile lookup. New templates: order confirmation, payment receipt, shipment dispatched. This is net-new user-facing behaviour, so ship it behind a per-type enable flag.
5. Observability: counters for received / deduped / sent / failed, per type and channel; alert on failure rate. Notes go in `docs/observability/`.

**Deviation — item 4 (commerce notifications) is deliberately NOT done.** Order-confirmation, payment-receipt and shipment-dispatched emails are *net-new user-facing behaviour*, not part of moving email out of `profile`: they would send customers mail they have never received before. They are also the only thing that needs the OAuth2 client and the profile recipient lookup, so deferring them keeps `notification` client-free for now (§3). Everything else in Phase 4 shipped. Treat commerce notifications as their own phase, sized and reviewed on its own merits.

**Verified against a live broker and database:**

| Check | Result |
|---|---|
| Fresh `PasswordChanged` | consumed, template rendered, `notification_log` row written |
| Same event id republished | `already processed — skipping`, no second send |
| `PasswordResetRequested` aged 45 min | `Dropping stale … 2701s old, max 300s`, logged `DROPPED_STALE` |
| Fresh `PasswordResetRequested` | rendered "Reset your password", link built from the token + configured origin |
| `UserRegistered` | rendered "Welcome to Granite Security" |
| Real registration through auth-server | published → consumed → rendered, ~30 ms end to end |

`processed_event` holds exactly 3 rows for 5 delivered messages: the duplicate did not insert a second row, and the stale event correctly never reached the dedupe step at all.

Status shows `SKIPPED_DISABLED` locally because `RESEND_API_KEY` is unset — the whole path is exercised bar the outbound HTTPS call, which `EmailChannelTest` covers.

---

### Phase 5 — Delete the old path

1. **auth-server:** delete the `profile-client` registration in `ProfileClientConfig`, the `MICROSERVICES_PROFILE_URI` env in `compose.yaml` and k8s, and the client-credentials `RegisteredClient` in `SecurityConfig` (~line 326) **if nothing else uses it** — check first; `profile` has its own `InternalClientConfig` and a `StorageClient`.
2. **profile:** delete the `notification/` package, `InternalNotificationHandler`, `PasswordChangedNotifyRequest`, `PasswordResetRequestedNotifyRequest`, the two `/api/profiles/internal/{username}/notify/**` routes in `ProfileRoute`, and the `RESEND_*` env from its deployment and `compose.yaml`. **Keep** the `SCOPE_internal` matcher in `ProfileSec` — the internal addresses route still needs it, and `notification` now calls the internal profile lookup.
3. **Docs:** `README.md` (ports, env vars, event flow), `docs/events/events.md` (add `identity.events` + payloads + the 1h retention note), `docs/architecture/microservices.puml` (component + edges), `CLAUDE.md` (layout table + architecture section, including the explicit note that auth-server produces fire-and-forget and deliberately has no outbox).

---

### Phase 6 (later) — Multi-channel

Cheap now, because the seams exist:

1. `TwilioSmsChannel implements NotificationChannel` + a phone number on the recipient model.
2. `notification_preferences (username, event_type, channel, enabled)` — per-user, per-type routing, replacing the Phase 2 stub.
3. WhatsApp via Twilio Content API; web push; in-app inbox (`GET /api/notifications`, reading `notification_log`).
   - **This is where `notification` becomes an OAuth2 resource server** (§3): add `spring-boot-starter-oauth2-resource-server`, a `NotificationSec` copied from `ProfileSec`, `jwt.trusted-issuers` / `jwk-set-uri` config, CORS, and a gateway route for `/api/notifications/**`. Until then it stays client-only.
4. Rate limiting / quiet hours / suppression list (hard bounces, unsubscribes) — all naturally owned here, all awkward to add in today's design.

## 6. Verification

**No dedicated test scaffolding, no dev-only endpoints, no Spring profiles for testing.** Verification is manual against a deployed cluster, using the real flows. This matches the repo's existing stance — CI's build step is `./gradlew build -x test`, so tests are not gating today either.

The only automated test is the existing `EmailServiceTest`, moved across from `profile` unchanged.

### 6.1 Deploying `kafka-ui` to the cluster — internal only

Worth doing. `kafka-ui` currently exists only in `compose.yaml` (`k8s/todo.md` lists deploying it as not yet done), and browsing topics beats `kafka-console-consumer` for the day-to-day work in Phases 3–5.

**Deploy it with no `HTTPRoute` and no DNS record.** Access it by port-forward:

```bash
kubectl -n granite port-forward deploy/kafka-ui 8090:8080   # → http://localhost:8090
```

#### Why not a public `kafka.granite-security.org`

`kafka-ui` ships with **no authentication** and full **read + write** access. A public hostname would mean anyone who finds it can:

- **Read every message on every topic.** After this refactor `identity.events` carries password reset tokens (D2). Polling that topic yields account takeover for any user — including `admin` and `manager` — before the real user sees their email. The 1-hour retention (§4.1) does not help: a poller sees everything inside the window.
- **Produce forged events.** Hand-produce a `PaymentReceived` onto `payments.events` and shop's `EventConsumer` moves the order to `PAID` with no Stripe involvement. Consumers parse untrusted JSON off the bus with no provenance check — correct while the bus is internal, catastrophic once it is not.
- **Delete topics**, unless read-only is set.

Port-forward gives the same visibility with zero attack surface and no Cloudflare record to maintain.

**Decided: no public hostname, port-forward only.** If that is ever revisited, the bar is all three together — `KAFKA_CLUSTERS_0_READONLY=true`, `AUTH_TYPE=OAUTH2` against our auth-server restricted to `ROLE_ADMIN`, and moving raw reset tokens off the bus (revisiting D2). Any subset leaves one of the two attacks above open.

**Image:** use `kafbat/kafka-ui` — the community continuation of the now-inactive `provectuslabs/kafka-ui` that `compose.yaml` still references. `:latest` is fine for this project.

### 6.2 Producing a test message by hand

If `kafka-ui` isn't deployed yet, the console tools in the broker pod do the job:

```bash
kubectl config current-context                     # always confirm first — multi-context kubeconfig

# watch the topic
kubectl -n granite exec -it deploy/kafka -- \
  kafka-console-consumer --bootstrap-server localhost:29092 \
    --topic identity.events --from-beginning

# hand-produce an event (Phase 4 — exercises the consumer without touching auth-server)
kubectl -n granite exec -it deploy/kafka -- \
  kafka-console-producer --bootstrap-server localhost:29092 \
    --topic identity.events --property parse.key=true --property key.separator=:
> ada:{"type":"PasswordChanged","username":"ada","email":"you@example.com","occurredAt":"2026-07-28T10:15:00Z"}
```

With `kafka-ui` deployed (§6.1) or locally via compose, the same thing is point-and-click.

### 6.3 Per-phase checks

| Phase | How to verify |
|---|---|
| 1 | `kubectl -n granite get pods` shows `notification` Running; `kubectl -n granite logs deploy/notification` shows Liquibase ran both changesets and Netty started on 8066 (no actuator in this repo — logs are the health signal) |
| 2 | Deploys and starts with `RESEND_API_KEY` set. No send path yet — nothing to trigger |
| 3 | Change a password in the UI → message appears on `identity.events` in the console consumer, **and** the email still arrives via the old profile path |
| 4 | Hand-produce as above → email arrives, `notification_log` has one row. Then repeat the same message → second one is deduped, **no** second email. Then produce one with a stale `occurredAt` → logged `DROPPED_STALE`, no email |
| 5 | Full flows once more after deletion: change password, reset password, register. Watch `kubectl -n granite logs deploy/profile` to confirm profile sends nothing |

### 6.4 Retention (needs a wall-clock wait, so don't forget it)

`retention.ms` + `segment.ms` (§4.1) can't be checked immediately — that's exactly why it's the kind of thing that silently never works. A day after Phase 3:

```bash
kubectl -n granite exec deploy/kafka -- \
  kafka-log-dirs --bootstrap-server localhost:29092 --describe \
    --topic-list identity.events
```

Segments should be rolling and old ones ageing out, not one ever-growing active segment.

### 6.5 The one thing manual testing won't catch

Mustache's `{{ }}` auto-escaping replaces the hand-rolled `escapeHtml` (Phase 2.3), and it is what keeps the reset link safe. A rendered email *looks* fine either way — the failure only shows with a hostile value, which no manual run will produce. Worth one deliberate check while verifying Phase 4: hand-produce a `PasswordResetRequested` whose token contains `"><b>x` and confirm the received email shows it as literal text rather than markup. One message, once — then it never needs re-testing.

## 7. Risks

| Risk | Mitigation |
|---|---|
| **Lost password-reset email** (accepted, no outbox) | Bounded and self-service: the user clicks "forgot password" again and gets a fresh token. Courtesy messages (password-changed, welcome) are invisible when lost. |
| Broker outage stalls the password-change response | `max.block.ms=2000` + `@Async` + catch-and-log — fails fast off the request thread (§2) |
| Reset token visible on the bus / in `kafka-ui` | D2 + §4.1: 1h topic retention (with `segment.ms` set, or it won't actually delete), single-use 30-min token; D4 + §6.1: `kafka-ui` stays cluster-internal, port-forward only, no public hostname |
| `kafka-ui` later given a public hostname → topic browsing yields reset tokens (account takeover), forged `PaymentReceived` yields free orders | §6.1: no `HTTPRoute`, port-forward only. Decided |
| Stale event replayed → unprompted "reset your password" email with a dead token | §4.1 staleness check at consume time; retention alone does **not** prevent this |
| `retention.ms` set but nothing ever deleted | `segment.ms=600000` alongside it; verify with `kafka-log-dirs` after a day that segments actually roll and age out |
| Duplicate emails from at-least-once consumption | `processed_events` dedupe, inserted before send (Phase 4.2) |
| Silent regression during the flip | Phase 3 dual-writes; Phase 4 flips by config only; Phase 5 deletes after a soak |
| Extra service = another Postgres in an already Postgres-heavy compose | Accepted; consistent with the one-DB-per-service convention |
| Someone "fixes" auth-server's fire-and-forget into an outbox | Documented as a deliberate decision here and in `CLAUDE.md` (Phase 5.3) |
| `GenericNotification` escape hatch becomes the default, degrading the design into a dumb send-pipe | Keep it explicitly marked and rare; review any new producer that reaches for it instead of a typed event (§3) |
| Producer adds a template field mid-rolling-deploy | Mustache renders absent keys as empty, so extra/missing optional fields are backward-compatible; missing **required** fields fail loudly and land in `notification_log` rather than sending half-rendered |

---

## 8. Follow-up: profile provisioning (shipped after Phases 1–4)

**Problem found in production.** A newly registered user's "My Profile" page showed no
email and no name. Pre-existing and unrelated to this refactor: `ProfileService`
lazily created `new UserProfile(username, null, null, null)` on first visit, and
nothing had ever told `profile` who a registered user was. auth-server collects email
and name on the registration form and writes them to `authdb`; that data never reached
`profiledb`, and it is not in the JWT either (the token customizer adds only `roles`).

**Fix.** `profile` became a **second consumer** of `UserRegistered` — the fan-out this
design was built for. auth-server's only change was carrying `firstName`/`lastName` on
the event it already published.

Provisioning **only fills fields that are currently null, and never overwrites**:

- Handles the race where the user opens "My Profile" before the event is consumed —
  the lazy stub already exists, and provisioning fills the blanks instead of fighting it.
- Makes redelivery a no-op, so no dedupe table is needed (unlike `notification`, where
  a duplicate means a duplicate email).
- A user who has since edited their details keeps them.

**Topic renamed `notifications.events` → `identity.events`.** The original name was a
misnomer: these are identity domain facts, and `docs/events/events.md` names topics for
the producing domain (`orders.events`, `payments.events`), not for a consumer. That was
tolerable with one consumer and actively confusing with two.

**Verified locally with both services running against one broker:**

| Check | Result |
|---|---|
| One `UserRegistered` | notification rendered the welcome mail **and** profile provisioned the row, independently |
| Provisioned row | `davide / davide@example.com / Davide / Rossi` |
| User edits name, event redelivered | edit preserved — `EditedByUser` not clobbered |
| Username-only stub, then event | blanks filled in |

**Not backfilled.** Existing users — including the `davide` account that surfaced this —
keep their stub rows: their `UserRegistered` was published to the old topic name and has
since aged out under the 1-hour retention. They either fill the form in once, or get a
one-off backfill from `authdb`. Everyone registering from now on is provisioned
automatically.
