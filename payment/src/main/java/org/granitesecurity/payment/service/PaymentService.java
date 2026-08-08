package org.granitesecurity.payment.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.granitesecurity.payment.domain.OutboxEvent;
import org.granitesecurity.payment.domain.Payment;
import org.granitesecurity.payment.domain.PaymentAttempt;
import org.granitesecurity.payment.domain.PaymentStatus;
import org.granitesecurity.payment.domain.Refund;
import org.granitesecurity.payment.domain.RefundStatus;
import org.granitesecurity.payment.dto.CreatePaymentIntentResponse;
import org.granitesecurity.payment.provider.CreateIntentRequest;
import org.granitesecurity.payment.provider.Money;
import org.granitesecurity.payment.provider.PaymentProvider;
import org.granitesecurity.payment.provider.PaymentProviderException;
import org.granitesecurity.payment.provider.PaymentProviderRegistry;
import org.granitesecurity.payment.provider.ProviderIntent;
import org.granitesecurity.payment.provider.RedirectPaymentProvider;
import org.granitesecurity.payment.repository.OutboxRepository;
import org.granitesecurity.payment.repository.PaymentAttemptRepository;
import org.granitesecurity.payment.repository.PaymentRepository;
import org.granitesecurity.payment.repository.RefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PaymentRepository paymentRepository;
    private final OutboxRepository outboxRepository;
    private final RefundRepository refundRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final PaymentProviderRegistry providers;

    @Value("${payment.shop-currency:${stripe.currency:chf}}")
    private String currency;

    /** API origin — where a REDIRECT provider sends the shopper back into this service. */
    @Value("${app.public-base-url:http://localhost:8080}")
    private String publicBaseUrl;

    /** SPA origin — where the shopper ends up once the payment is finalized. */
    @Value("${app.frontend-origin:http://localhost:5173}")
    private String frontendOrigin;

    public PaymentService(PaymentRepository paymentRepository,
                          OutboxRepository outboxRepository,
                          RefundRepository refundRepository,
                          PaymentAttemptRepository attemptRepository,
                          PaymentProviderRegistry providers) {
        this.paymentRepository = paymentRepository;
        this.outboxRepository = outboxRepository;
        this.refundRepository = refundRepository;
        this.attemptRepository = attemptRepository;
        this.providers = providers;
    }

    /**
     * The adapter for an existing payment, resolved from the row rather than from
     * config: a payment opened against one provider must keep being reconciled and
     * refunded against that provider even after the configured default changes.
     *
     * <p>Rows written before the {@code provider} column was populated fall back to
     * the single enabled provider, which is correct while Stripe is the only one and
     * throws loudly once it is not.
     */
    /**
     * The provider for a payment being opened. A caller that named none is relying on
     * there being exactly one enabled — true locally, false in the cluster, where both
     * Stripe and PayPal are on. That case reaches here from the OrderPlaced consumer,
     * whose catch-all would otherwise turn it into a logged "failed to handle" and an
     * order that quietly never gets an intent, so name it before rethrowing.
     */
    private PaymentProvider resolveProvider(String providerName, String context) {
        if (providerName != null) {
            return providers.get(providerName);
        }
        try {
            return providers.defaultProvider();
        } catch (PaymentProviderRegistry.AmbiguousProviderException e) {
            log.error("No payment provider named for {} and several are enabled — "
                    + "no intent will be opened: {}", context, e.getMessage());
            throw e;
        }
    }

    private PaymentProvider providerFor(Payment payment) {
        String name = payment.getProvider();
        if (name == null || name.isBlank()) {
            PaymentProvider fallback = providers.defaultProvider();
            log.warn("Payment {} (order {}) has no provider recorded, assuming {}",
                    payment.getId(), payment.getOrderId(), fallback.name());
            return fallback;
        }
        return providers.get(name);
    }

    /**
     * @param currencyOverride the currency shop priced the order in, or null for events
     *                         published before shop carried one. Honoured rather than
     *                         re-derived: payment must charge what shop quoted, even if
     *                         the configured shop currency has since changed.
     * @param providerName     the shopper's chosen provider, or null for the only
     *                         enabled one
     */
    public Mono<Void> processOrderPlaced(Long orderId, BigDecimal total, String username,
                                         String currencyOverride, String providerName) {
        return paymentRepository.findByOrderId(orderId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.info("Creating payment for order {} from OrderPlaced event", orderId);
                    return doCreatePaymentIntent(orderId, total, currencyOverride, username,
                            "payment-order-async-", providerName);
                }))
                .doOnNext(existing -> log.info("Payment already exists for order {}, skipping", orderId))
                .then();
    }

    public Mono<Void> processOrderPlaced(Long orderId, BigDecimal total, String username) {
        return processOrderPlaced(orderId, total, username, null, null);
    }

    public Mono<CreatePaymentIntentResponse> getPaymentByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .flatMap(payment -> refundRepository.findByOrderId(orderId)
                        .map(refund -> toResponse(payment, toRefundInfo(refund)))
                        .defaultIfEmpty(toResponse(payment, null)));
    }

    /**
     * Payment status per order id, for shop's purge-eligibility check. Orders
     * with no payment row are simply absent from the result — shop treats a
     * missing entry as "no money moved", which is what it means.
     */
    public Mono<Map<Long, String>> statusesByOrderIds(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        return paymentRepository.findByOrderIdIn(orderIds)
                .collectMap(Payment::getOrderId, Payment::getStatus);
    }

    /**
     * Drops this service's rows for orders that shop has purged. Keyed by
     * order_id — payment has no username to match on, which is why shop
     * resolves the mapping and publishes the ids (docs/users/blocking-users.md
     * §6).
     *
     * <p>Idempotent by construction: deleting rows that are already gone is a
     * no-op, so an at-least-once redelivery needs no dedupe table.
     *
     * <p><b>provider_event is deliberately left alone.</b> It is the webhook
     * dedupe log — clearing it would let already-processed webhooks replay
     * against a payment that no longer exists.
     *
     * <p>payment_attempt needs no explicit delete: it is {@code ON DELETE CASCADE}
     * on payment_id, so the attempts go with the payment they belong to.
     */
    public Mono<Void> purgeOrders(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Mono.empty();
        }
        // Refunds first: they reference the payment. A purgeable user cannot
        // have a REFUNDED payment (§4.2 blocks that), but a refund row in a
        // non-terminal state can still exist, and leaving it would orphan it.
        return refundRepository.deleteByOrderIdIn(orderIds)
                .then(paymentRepository.deleteByOrderIdIn(orderIds))
                .doOnSuccess(deleted -> log.info("Purged {} payment row(s) for {} order(s)",
                        deleted, orderIds.size()))
                .then();
    }

    /** The order ids we hold payment rows for — orphan sweep (§8 Phase 6). */
    public Mono<java.util.List<Long>> distinctOrderIds() {
        return paymentRepository.findDistinctOrderIds().collectList();
    }

    public static CreatePaymentIntentResponse toResponse(Payment payment, CreatePaymentIntentResponse.RefundInfo refund) {
        return CreatePaymentIntentResponse.of(
                payment.getId(),
                payment.getOrderId(),
                payment.getProvider(),
                payment.getProviderPaymentId(),
                payloadMap(payment.getProviderPayload()),
                payment.getStatus(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getCreatedAt(),
                refund);
    }

    /**
     * {@code provider_payload} is JSON as of migration 005, and is served as an object
     * rather than a string so a REDIRECT provider can put a URL in it.
     *
     * <p>Tolerates a bare string: the migration rewrote existing rows to JSON, but a row
     * written by an older instance mid-rollout would not be. Such a value is by
     * definition a client secret, since CLIENT_SDK was all that existed then.
     */
    static Map<String, Object> payloadMap(String providerPayload) {
        if (providerPayload == null || providerPayload.isBlank()) {
            return null;
        }
        if (!providerPayload.startsWith("{")) {
            return Map.of("clientSecret", providerPayload);
        }
        try {
            return MAPPER.readValue(providerPayload, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("provider_payload is not readable JSON, omitting it: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Builds the request for a provider, with the redirect URLs a REDIRECT provider
     * needs. Both are always populated: a CLIENT_SDK adapter ignores them (Stripe does),
     * and computing them here rather than per-adapter keeps the one piece of knowledge
     * that is genuinely ours — our own public URLs — out of the adapters.
     *
     * <p>They are built from config, not from a request, because the main creation path
     * is {@code OrderPlacedConsumer} — a Kafka consumer with no HTTP request in flight.
     */
    private CreateIntentRequest intentRequest(PaymentProvider provider, Long orderId, Money amount,
                                              String username, String idempotencyKey) {
        return new CreateIntentRequest(
                orderId,
                amount,
                username,
                idempotencyKey,
                trimSlash(publicBaseUrl) + "/api/payments/return/" + provider.name() + "?orderId=" + orderId,
                trimSlash(frontendOrigin) + "/orders/" + orderId + "?payment=cancelled",
                // Null means "same as the order id" — unchanged behaviour for orders.
                null);
    }

    /** Where the shopper is sent once a redirect payment is finalized, success or not. */
    public String orderPageUrl(Long orderId) {
        return trimSlash(frontendOrigin) + "/orders/" + orderId;
    }

    /** Where the shopper lands after a top-up, success or not. */
    public String balancePageUrl() {
        return trimSlash(frontendOrigin) + "/profile/balance";
    }

    /** Fallback landing page when a return carries no usable order id. */
    public String ordersPageUrl() {
        return trimSlash(frontendOrigin) + "/orders";
    }

    private static String trimSlash(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /**
     * Serializes a provider's payload map for storage. Null when there is nothing to keep.
     *
     * <p>{@code redirectUrl} is merged into the map rather than kept as the separate
     * record field it arrives in. The frontend reads {@code providerPayload.redirectUrl}
     * (see {@code RedirectPaymentWidget}), and the column is the only thing that reaches
     * it — a value left in the record field alone would be silently dropped on save.
     */
    static String toProviderPayload(ProviderIntent intent) {
        Map<String, Object> payload = new LinkedHashMap<>(intent.payload());
        if (intent.redirectUrl() != null && !intent.redirectUrl().isBlank()) {
            payload.put("redirectUrl", intent.redirectUrl());
        }
        if (payload.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Failed to serialize provider payload for {}", intent.providerPaymentId(), e);
            return null;
        }
    }

    public Mono<Void> processRefundRequested(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Refund requested for order {} but no payment found, skipping", orderId);
                    return Mono.empty();
                }))
                .flatMap(payment -> {
                    if (!PaymentStatus.SUCCEEDED.name().equals(payment.getStatus())) {
                        log.warn("Refund requested for order {} but payment status is {}, skipping",
                                orderId, payment.getStatus());
                        return Mono.empty();
                    }
                    return refundRepository.findByOrderId(orderId)
                            .map(Optional::of)
                            .defaultIfEmpty(Optional.empty())
                            .flatMap(refundOpt -> {
                                if (refundOpt.isPresent()) {
                                    Refund existing = refundOpt.get();
                                    if (RefundStatus.SUCCEEDED.name().equals(existing.getStatus())) {
                                        log.info("Refund already succeeded for order {}, republishing PaymentRefunded event", orderId);
                                        return publishPaymentRefundedEvent(payment.getProvider(), existing);
                                    }
                                    log.info("Refund for order {} in status {}, retrying Stripe refund", orderId, existing.getStatus());
                                    return executeRefund(payment, existing);
                                }
                                Refund refund = new Refund(orderId, payment.getId(), payment.getAmount());
                                refund.setCreatedAt(Instant.now());
                                refund.setUpdatedAt(Instant.now());
                                return refundRepository.save(refund)
                                        .flatMap(saved -> executeRefund(payment, saved));
                            });
                })
                .then();
    }

    private Mono<Void> executeRefund(Payment payment, Refund refund) {
        Long orderId = payment.getOrderId();
        PaymentProvider provider = providerFor(payment);
        Money amount = new Money(payment.getAmount(), payment.getCurrency());

        return provider.createRefund(payment.getProviderPaymentId(), amount, "refund-order-" + orderId)
                .flatMap(providerRefund -> {
                    // Unchanged from before the seam: a refund the provider accepted is
                    // recorded as SUCCEEDED regardless of the status string it came back
                    // with; a pending one is reconciled later by /sync.
                    refund.setStatus(RefundStatus.SUCCEEDED.name());
                    refund.setProviderRefundId(providerRefund.providerRefundId());
                    refund.setUpdatedAt(Instant.now());
                    refund.markNotNew();

                    payment.setStatus(PaymentStatus.REFUNDED.name());
                    payment.setUpdatedAt(Instant.now());
                    payment.markNotNew();

                    return refundRepository.save(refund)
                            .then(paymentRepository.save(payment))
                            .then(publishPaymentRefundedEvent(payment.getProvider(), refund))
                            .doOnSuccess(v -> log.info("Refunded order {} via {} refund {}",
                                    orderId, provider.name(), providerRefund.providerRefundId()));
                })
                // Only provider failures mark the refund FAILED. A persistence error must
                // propagate, or a refund Stripe actually made would be recorded as failed.
                .onErrorResume(PaymentProviderException.class, e -> {
                    log.error("{} API error refunding order {}: {}", provider.name(), orderId, e.getMessage(), e);
                    refund.setStatus(RefundStatus.FAILED.name());
                    refund.setUpdatedAt(Instant.now());
                    refund.markNotNew();
                    return refundRepository.save(refund).then();
                });
    }

    /**
     * @param provider the provider that issued the refund. Published as a new key
     *                 alongside the legacy {@code stripeRefundId} rather than replacing
     *                 it, so messages in flight during a rollout still match shop's
     *                 existing branches.
     */
    private Mono<Void> publishPaymentRefundedEvent(String provider, Refund refund) {
        try {
            Map<String, Object> eventPayload = new LinkedHashMap<>();
            eventPayload.put("orderId", refund.getOrderId());
            eventPayload.put("status", PaymentStatus.REFUNDED.name());
            eventPayload.put("provider", provider);
            eventPayload.put("providerRefundId", refund.getProviderRefundId());
            eventPayload.put("stripeRefundId", refund.getProviderRefundId());
            eventPayload.put("amount", refund.getAmount());
            eventPayload.put("refundedAt", refund.getUpdatedAt() != null ? refund.getUpdatedAt() : Instant.now());
            String json = MAPPER.writeValueAsString(eventPayload);
            OutboxEvent outbox = new OutboxEvent(
                    "payment",
                    String.valueOf(refund.getOrderId()),
                    "PaymentRefunded",
                    json,
                    "PENDING"
            );
            return outboxRepository.save(outbox).then();
        } catch (Exception e) {
            log.error("Failed to serialize PaymentRefunded event", e);
            return Mono.error(e);
        }
    }

    /** Mirrors WebhookHandler's event, so both paths tell shop the same thing. */
    private Mono<Void> publishRefundFailedEvent(String provider, Refund refund) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("orderId", refund.getOrderId());
            // Deliberately not "FAILED": shop's consumer switches on this value and
            // already maps FAILED to PAYMENT_FAILED, which would mark the order as
            // never paid rather than walking the refund back.
            payload.put("status", "REFUND_FAILED");
            payload.put("provider", provider);
            payload.put("providerRefundId", refund.getProviderRefundId());
            payload.put("amount", refund.getAmount());
            payload.put("failedAt", Instant.now());
            OutboxEvent outbox = new OutboxEvent("payment", String.valueOf(refund.getOrderId()),
                    "PaymentRefundFailed", MAPPER.writeValueAsString(payload), "PENDING");
            return outboxRepository.save(outbox).then();
        } catch (Exception e) {
            log.error("Failed to serialize PaymentRefundFailed event", e);
            return Mono.error(e);
        }
    }

    public Mono<Payment> createPaymentIntent(Long orderId, BigDecimal total, String currencyOverride,
                                             String username, String providerName) {
        return paymentRepository.findByOrderId(orderId)
                .flatMap(existing -> {
                    log.info("Payment already exists for order {}, returning existing", orderId);
                    return Mono.just(existing);
                })
                .switchIfEmpty(Mono.defer(() -> doCreatePaymentIntent(
                        orderId, total, currencyOverride, username, "payment-order-", providerName)));
    }

    public Mono<Payment> createPaymentIntent(Long orderId, BigDecimal total, String currencyOverride, String username) {
        return createPaymentIntent(orderId, total, currencyOverride, username, null);
    }

    public Mono<Payment> createPaymentIntent(Long orderId, BigDecimal total, String username) {
        return createPaymentIntent(orderId, total, currency, username);
    }

    public Mono<Payment> retryPaymentIntent(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .switchIfEmpty(Mono.error(new RuntimeException("Payment not found for order " + orderId)))
                .flatMap(existing -> {
                    if (PaymentStatus.SUCCEEDED.name().equals(existing.getStatus())) {
                        return Mono.error(new RuntimeException("Payment already completed for order " + orderId));
                    }

                    PaymentProvider provider = providerFor(existing);
                    // Username is deliberately null here: retry never sent it before the
                    // seam, and the adapter omits the metadata key when it is null.
                    var request = intentRequest(
                            provider,
                            orderId,
                            new Money(existing.getAmount(), existing.getCurrency()),
                            null,
                            "payment-retry-" + orderId + "-" + UUID.randomUUID());

                    return provider.recreateIntent(request, existing.getProviderPaymentId())
                            // A retry is a new attempt, not an edit of the old one: the
                            // abandoned intent keeps its own row so /sync can still
                            // reconcile it and so the audit trail survives.
                            .flatMap(intent -> recordAttempt(existing, provider, intent)
                                    .flatMap(attempt -> {
                                        existing.setProviderPaymentId(intent.providerPaymentId());
                                        existing.setProviderPayload(toProviderPayload(intent));
                                        existing.setCurrentAttemptId(attempt.getId());
                                        existing.setStatus(PaymentStatus.CREATED.name());
                                        existing.setUpdatedAt(Instant.now());
                                        existing.markNotNew();
                                        return paymentRepository.save(existing);
                                    }))
                            .flatMap(saved -> publishIntentCreatedEvent(saved).thenReturn(saved))
                            .doOnSuccess(saved -> log.info("Retried {} payment {} for order {}",
                                    provider.name(), saved.getProviderPaymentId(), orderId))
                            .onErrorResume(PaymentProviderException.class, e -> {
                                log.error("{} API error retrying payment for order {}: {}",
                                        provider.name(), orderId, e.getMessage(), e);
                                return Mono.error(e);
                            });
                });
    }

    /**
     * Takes the money for a payment the shopper has just approved at a redirect
     * provider, and applies whatever status comes back.
     *
     * <p>Called from two places that race by design: the shopper's return to
     * {@code /api/payments/return/{provider}}, and the provider's own webhook. Both
     * paths are needed — a shopper who approves and then closes the tab never returns,
     * and a webhook that is disabled or delayed leaves the shopper staring at a pending
     * order. {@link RedirectPaymentProvider#finalizePayment} is required to be
     * idempotent precisely so this can be entered twice.
     *
     * <p>The {@code provider} argument is checked against the stored row rather than
     * trusted: it arrives from a URL the shopper's browser was redirected to, so it is
     * attacker-controllable. Nothing here reads the provider's other query parameters
     * for the same reason — the capture call is the only authority on whether money
     * moved.
     */
    public Mono<Payment> finalizeRedirectPayment(String providerName, Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .switchIfEmpty(Mono.error(new RuntimeException("Payment not found for order " + orderId)))
                .flatMap(payment -> {
                    PaymentProvider provider = providerFor(payment);
                    if (!provider.name().equalsIgnoreCase(providerName)) {
                        // Someone hit paypal's return URL for a Stripe order, or the
                        // shopper retried with a different provider between approve and
                        // return. Either way this row is not ours to finalize.
                        log.warn("Return for '{}' on order {} but the payment is held by '{}' — ignoring",
                                providerName, orderId, provider.name());
                        return Mono.just(payment);
                    }
                    if (!(provider instanceof RedirectPaymentProvider redirect)) {
                        log.warn("Return for order {} but provider '{}' is not redirect-shaped — ignoring",
                                orderId, provider.name());
                        return Mono.just(payment);
                    }
                    if (payment.getProviderPaymentId() == null) {
                        return Mono.error(new RuntimeException("No provider payment for order " + orderId));
                    }
                    if (PaymentStatus.SUCCEEDED.name().equals(payment.getStatus())) {
                        log.info("Order {} already succeeded, nothing to finalize", orderId);
                        return Mono.just(payment);
                    }
                    return redirect.finalizePayment(payment.getProviderPaymentId())
                            .flatMap(intent -> applyProviderStatus(payment, intent))
                            // A provider failure must not blank the shopper's page: they
                            // still get sent to the order, which shows it unpaid and
                            // offers a retry. /sync and the webhook will reconcile.
                            .onErrorResume(PaymentProviderException.class, e -> {
                                log.error("{} failed to finalize order {}: {}",
                                        provider.name(), orderId, e.getMessage(), e);
                                return Mono.just(payment);
                            });
                });
    }

    /**
     * Opens a payment that funds a balance rather than an order (finance.md §6.1).
     *
     * <p>Same providers, same intents. The differences are that there is no order, so
     * the intent is referenced by its own payment id, and that the shopper returns to
     * {@code ?paymentId=} rather than {@code ?orderId=}.
     *
     * <p><b>Confirmation is the return endpoint plus {@link #syncTopup}.</b> Provider
     * webhooks cannot resolve a top-up: both adapters map an inbound event back to a
     * payment through an order id, and a top-up has none. A shopper who abandons the
     * provider tab therefore needs a sync to reconcile — see finance.md §6.1.
     */
    public Mono<Payment> createTopupIntent(String username, BigDecimal amount,
                                           String currencyOverride, String providerName) {
        String cur = currencyOverride != null ? currencyOverride : currency;
        PaymentProvider provider = providerName == null ? providers.defaultProvider() : providers.get(providerName);

        Payment payment = Payment.topup(username, amount, cur.toUpperCase(), provider.name());
        String reference = payment.getId().toString();

        var request = new CreateIntentRequest(
                null,
                new Money(amount, cur),
                username,
                "payment-topup-" + reference,
                trimSlash(publicBaseUrl) + "/api/payments/return/" + provider.name() + "?paymentId=" + reference,
                trimSlash(frontendOrigin) + "/profile/balance?topup=cancelled",
                reference);

        return provider.createIntent(request)
                .flatMap(intent -> {
                    payment.setProviderPaymentId(intent.providerPaymentId());
                    payment.setProviderPayload(toProviderPayload(intent));
                    payment.setStatus(PaymentStatus.CREATED.name());
                    payment.setCreatedAt(Instant.now());
                    payment.setUpdatedAt(Instant.now());
                    return paymentRepository.save(payment);
                })
                .doOnSuccess(saved -> log.info("Top-up {} opened at {} for {} ({} {})",
                        saved.getId(), provider.name(), username, amount, cur));
    }

    /** Confirms a top-up against the provider. The only reliable path for one. */
    public Mono<Payment> syncTopup(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .switchIfEmpty(Mono.error(new RuntimeException("No payment " + paymentId)))
                .flatMap(payment -> {
                    if (payment.getProviderPaymentId() == null) {
                        return Mono.error(new RuntimeException("No provider payment for " + paymentId));
                    }
                    return providerFor(payment).retrieveIntent(payment.getProviderPaymentId())
                            .flatMap(intent -> applyProviderStatus(payment, intent));
                });
    }

    /**
     * The redirect return for a top-up. Same two-step as an order: capture, then apply.
     * Idempotent, because the shopper can refresh it.
     */
    public Mono<Payment> finalizeRedirectTopup(String providerName, UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .switchIfEmpty(Mono.error(new RuntimeException("No payment " + paymentId)))
                .flatMap(payment -> {
                    PaymentProvider provider = providerFor(payment);
                    if (!provider.name().equalsIgnoreCase(providerName)) {
                        log.warn("Return for '{}' on top-up {} but it is held by '{}' — ignoring",
                                providerName, paymentId, provider.name());
                        return Mono.just(payment);
                    }
                    if (PaymentStatus.SUCCEEDED.name().equals(payment.getStatus())) {
                        return Mono.just(payment);
                    }
                    if (!(provider instanceof RedirectPaymentProvider redirect)) {
                        return syncTopup(paymentId);
                    }
                    return redirect.finalizePayment(payment.getProviderPaymentId())
                            .flatMap(intent -> applyProviderStatus(payment, intent))
                            .onErrorResume(PaymentProviderException.class, e -> {
                                log.error("{} failed to finalize top-up {}: {}",
                                        provider.name(), paymentId, e.getMessage(), e);
                                return Mono.just(payment);
                            });
                });
    }

    public Mono<CreatePaymentIntentResponse> syncPaymentStatus(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .switchIfEmpty(Mono.error(new RuntimeException("Payment not found for order " + orderId)))
                .flatMap(payment -> {
                    String providerPaymentId = payment.getProviderPaymentId();
                    if (providerPaymentId == null) {
                        return Mono.error(new RuntimeException("No provider payment for order " + orderId));
                    }
                    return providerFor(payment).retrieveIntent(providerPaymentId)
                            .flatMap(intent -> applyProviderStatus(payment, intent));
                })
                .flatMap(payment -> reconcileRefund(payment).thenReturn(payment))
                .flatMap(payment -> refundRepository.findByOrderId(orderId)
                        .map(refund -> toResponse(payment, toRefundInfo(refund)))
                        .defaultIfEmpty(toResponse(payment, null)));
    }

    private static CreatePaymentIntentResponse.RefundInfo toRefundInfo(Refund refund) {
        return CreatePaymentIntentResponse.RefundInfo.of(
                refund.getProviderRefundId(),
                refund.getAmount(),
                refund.getStatus(),
                refund.getCreatedAt());
    }

    private Mono<Void> reconcileRefund(Payment payment) {
        Long orderId = payment.getOrderId();
        return refundRepository.findByOrderId(orderId)
                .flatMap(refund -> {
                    if (refund.getProviderRefundId() != null && !refund.getProviderRefundId().isBlank()) {
                        // Re-checked even when already SUCCEEDED. executeRefund records
                        // SUCCEEDED the moment the provider accepts, but a refund can
                        // still fail afterwards at the bank; skipping the check here is
                        // what made that failure permanently invisible.
                        return syncRefundFromProvider(payment, refund);
                    }
                    if (RefundStatus.SUCCEEDED.name().equals(refund.getStatus())) {
                        return Mono.empty();   // succeeded with no id to verify against
                    }
                    // PENDING/FAILED without a Stripe refund id — the create call never completed,
                    // so re-attempt; the fixed idempotency key makes this safe.
                    log.info("Re-attempting refund for order {} via /sync (status {}, no Stripe refund id)",
                            orderId, refund.getStatus());
                    return executeRefund(payment, refund);
                });
    }

    private Mono<Void> syncRefundFromProvider(Payment payment, Refund refund) {
        return providerFor(payment).retrieveRefund(refund.getProviderRefundId())
                .flatMap(providerRefund -> {
                    RefundStatus newStatus = providerRefund.status();
                    if (newStatus == null || newStatus.name().equals(refund.getStatus())) {
                        return Mono.<Void>empty();
                    }
                    refund.setStatus(newStatus.name());
                    refund.setUpdatedAt(Instant.now());
                    refund.markNotNew();

                    Mono<Void> saveRefund = refundRepository.save(refund).then();
                    if (newStatus == RefundStatus.FAILED) {
                        // The provider walked a refund back. Restore the payment: we are
                        // still holding the money, and shop has the order in REIMBURSED.
                        log.error("Refund {} for order {} reported FAILED by the provider — unwinding",
                                refund.getProviderRefundId(), payment.getOrderId());
                        payment.setStatus(PaymentStatus.SUCCEEDED.name());
                        payment.setUpdatedAt(Instant.now());
                        payment.markNotNew();
                        return saveRefund
                                .then(paymentRepository.save(payment))
                                .then(publishRefundFailedEvent(payment.getProvider(), refund));
                    }
                    if (newStatus != RefundStatus.SUCCEEDED) {
                        return saveRefund;
                    }
                    payment.setStatus(PaymentStatus.REFUNDED.name());
                    payment.setUpdatedAt(Instant.now());
                    payment.markNotNew();
                    return saveRefund
                            .then(paymentRepository.save(payment))
                            .then(publishPaymentRefundedEvent(payment.getProvider(), refund))
                            .doOnSuccess(v -> log.info("Refund {} for order {} reconciled to SUCCEEDED via /sync",
                                    refund.getProviderRefundId(), payment.getOrderId()));
                })
                .onErrorResume(PaymentProviderException.class, e -> {
                    log.error("Provider error retrieving refund {} for order {}: {}",
                            refund.getProviderRefundId(), payment.getOrderId(), e.getMessage(), e);
                    return Mono.empty();
                });
    }

    /**
     * Applies a status the provider reported. A null {@code status} means the provider
     * is in a state we deliberately do not act on (Stripe's requires_payment_method,
     * say), so the stored status is left alone — same as before the seam.
     */
    private Mono<Payment> applyProviderStatus(Payment payment, ProviderIntent intent) {
        PaymentStatus mapped = intent.status();
        if (mapped == null || mapped.name().equals(payment.getStatus())) {
            return Mono.just(payment);
        }
        // A refunded payment's intent still reads "succeeded" at the provider — the
        // charge did succeed; the money was returned separately. Without this guard,
        // /sync on a refunded order walks the row back to SUCCEEDED and publishes
        // PaymentSucceeded, telling shop an order it already reimbursed was just paid.
        if (PaymentStatus.REFUNDED.name().equals(payment.getStatus()) && mapped == PaymentStatus.SUCCEEDED) {
            return Mono.just(payment);
        }

        payment.setStatus(mapped.name());
        payment.setUpdatedAt(Instant.now());
        payment.markNotNew();

        return paymentRepository.save(payment)
                .flatMap(saved -> advanceCurrentAttempt(saved, mapped, intent.declineReason()).thenReturn(saved))
                .flatMap(saved -> publishStatusEvent(saved, mapped.name()).thenReturn(saved));
    }

    /** Opens a row recording this try at taking the money. */
    private Mono<PaymentAttempt> recordAttempt(Payment payment, PaymentProvider provider, ProviderIntent intent) {
        PaymentAttempt attempt = new PaymentAttempt(
                payment.getId(), payment.getOrderId(), provider.name(),
                payment.getAmount(), payment.getCurrency());
        attempt.setProviderPaymentId(intent.providerPaymentId());
        attempt.setProviderPayload(toProviderPayload(intent));
        attempt.setDeclineReason(intent.declineReason());
        return attemptRepository.save(attempt);
    }

    /**
     * Moves the attempt in play to match the payment.
     *
     * <p>Best-effort by design: the attempt table is an audit trail, and failing a
     * shopper's {@code /sync} because a history row would not write is the wrong
     * trade. The unique index on succeeded attempts is the part that must not be
     * papered over, so a violation there is logged loudly.
     */
    private Mono<Void> advanceCurrentAttempt(Payment payment, PaymentStatus status, String declineReason) {
        if (payment.getCurrentAttemptId() == null) {
            return Mono.empty();     // predates payment_attempt; nothing to advance
        }
        return attemptRepository.findById(payment.getCurrentAttemptId())
                .flatMap(attempt -> {
                    if (status.name().equals(attempt.getStatus())) {
                        return Mono.<PaymentAttempt>empty();
                    }
                    attempt.setStatus(status.name());
                    if (declineReason != null) {
                        attempt.setDeclineReason(declineReason);
                    }
                    attempt.setUpdatedAt(Instant.now());
                    attempt.markNotNew();
                    return attemptRepository.save(attempt);
                })
                .onErrorResume(e -> {
                    log.error("Could not advance attempt {} for order {} to {}: {}",
                            payment.getCurrentAttemptId(), payment.getOrderId(), status, e.getMessage(), e);
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> publishStatusEvent(Payment payment, String newStatus) {
        String eventName = switch (newStatus) {
            case "SUCCEEDED" -> "PaymentSucceeded";
            case "FAILED" -> "PaymentFailed";
            case "CANCELED" -> "PaymentCanceled";
            default -> null;
        };
        if (eventName == null) return Mono.empty();

        try {
            Map<String, Object> eventPayload = new LinkedHashMap<>();
            eventPayload.put("orderId", payment.getOrderId());
            eventPayload.put("provider", payment.getProvider());
            eventPayload.put("providerPaymentId", payment.getProviderPaymentId());
            // Legacy alias, kept alongside so in-flight messages still match shop's
            // existing branch during a rollout.
            eventPayload.put("stripePaymentIntentId", payment.getProviderPaymentId());
            eventPayload.put("status", payment.getStatus());
            // A top-up is money entering the system, so balance needs to know who
            // and how much. paymentId is what makes crediting idempotent on the
            // consumer side (docs/finance/finance.md §6.1, D9).
            eventPayload.put("purpose", payment.getPurpose());
            eventPayload.put("paymentId", payment.getId().toString());
            eventPayload.put("username", payment.getUsername());
            eventPayload.put("amount", payment.getAmount());
            eventPayload.put("currency", payment.getCurrency());
            String json = MAPPER.writeValueAsString(eventPayload);
            OutboxEvent outbox = new OutboxEvent(
                    "payment",
                    // A top-up has no order, so it keys on the payment itself.
                    payment.getOrderId() != null
                            ? String.valueOf(payment.getOrderId())
                            : payment.getId().toString(),
                    eventName,
                    json,
                    "PENDING"
            );
            return outboxRepository.save(outbox).then();
        } catch (Exception e) {
            log.error("Failed to serialize {} event", eventName, e);
            return Mono.error(e);
        }
    }

    private Mono<Void> publishIntentCreatedEvent(Payment saved) {
        // No clientSecret: it is a payment-confirmation credential and payments.events
        // is not the place for one. No consumer ever read it — both frontends fetch it
        // from GET /api/payments/intent/{orderId}. Same reasoning as identity.events.
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("orderId", saved.getOrderId());
        eventPayload.put("provider", saved.getProvider());
        eventPayload.put("providerPaymentId", saved.getProviderPaymentId());
        // Legacy alias — shop detects PaymentIntentCreated by this key's presence.
        eventPayload.put("stripePaymentIntentId", saved.getProviderPaymentId());
        eventPayload.put("amount", saved.getAmount());
        eventPayload.put("currency", saved.getCurrency());
        try {
            String json = MAPPER.writeValueAsString(eventPayload);
            OutboxEvent outboxEvent = new OutboxEvent(
                    "payment",
                    String.valueOf(saved.getOrderId()),
                    "PaymentIntentCreated",
                    json,
                    "PENDING"
            );
            return outboxRepository.save(outboxEvent).then();
        } catch (Exception e) {
            log.error("Failed to serialize PaymentIntentCreated payload", e);
            return Mono.error(e);
        }
    }

    private Mono<Payment> doCreatePaymentIntent(Long orderId, BigDecimal total, String currencyOverride, String username, String idempotencyPrefix) {
        return doCreatePaymentIntent(orderId, total, currencyOverride, username, idempotencyPrefix, null);
    }

    /**
     * Opens a payment at a provider and records it.
     *
     * @param providerName which provider to charge, or null for the only enabled one.
     *                     While one provider is enabled this is always null in practice;
     *                     it becomes a shopper choice in step 3 of the refactor plan.
     */
    private Mono<Payment> doCreatePaymentIntent(Long orderId, BigDecimal total, String currencyOverride,
                                                String username, String idempotencyPrefix, String providerName) {
        String cur = currencyOverride != null ? currencyOverride : currency;
        PaymentProvider provider = resolveProvider(providerName, "order " + orderId);

        // Username is passed as "" rather than null when unknown: create always sent the
        // metadata key before the seam, and the adapter omits it only for null.
        var request = intentRequest(
                provider,
                orderId,
                new Money(total, cur),
                username != null ? username : "",
                idempotencyPrefix + orderId);

        return provider.createIntent(request)
                .flatMap(intent -> {
                    Payment payment = new Payment(orderId, total, cur.toUpperCase(), provider.name());
                    payment.setProviderPaymentId(intent.providerPaymentId());
                    payment.setProviderPayload(toProviderPayload(intent));
                    payment.setStatus(PaymentStatus.CREATED.name());
                    payment.setCreatedAt(Instant.now());
                    payment.setUpdatedAt(Instant.now());

                    // The payment row must exist before the attempt: payment_attempt
                    // references it.
                    return paymentRepository.save(payment)
                            .flatMap(saved -> recordAttempt(saved, provider, intent)
                                    .flatMap(attempt -> {
                                        saved.setCurrentAttemptId(attempt.getId());
                                        saved.markNotNew();
                                        return paymentRepository.save(saved);
                                    }))
                            .flatMap(saved -> publishIntentCreatedEvent(saved).thenReturn(saved))
                            .doOnSuccess(saved -> log.info("Created {} payment {} for order {}",
                                    provider.name(), saved.getProviderPaymentId(), orderId));
                })
                .onErrorResume(PaymentProviderException.class, e -> {
                    log.error("{} API error creating payment for order {}: {}",
                            provider.name(), orderId, e.getMessage(), e);
                    return Mono.error(e);
                });
    }
}
