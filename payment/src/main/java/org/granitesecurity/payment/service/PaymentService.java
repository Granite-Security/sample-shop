package org.granitesecurity.payment.service;

import tools.jackson.databind.ObjectMapper;
import org.granitesecurity.payment.domain.OutboxEvent;
import org.granitesecurity.payment.domain.Payment;
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
import org.granitesecurity.payment.repository.OutboxRepository;
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
    private final PaymentProviderRegistry providers;

    @Value("${payment.shop-currency:${stripe.currency:chf}}")
    private String currency;

    public PaymentService(PaymentRepository paymentRepository,
                          OutboxRepository outboxRepository,
                          RefundRepository refundRepository,
                          PaymentProviderRegistry providers) {
        this.paymentRepository = paymentRepository;
        this.outboxRepository = outboxRepository;
        this.refundRepository = refundRepository;
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

    public Mono<Void> processOrderPlaced(Long orderId, BigDecimal total, String username) {
        return paymentRepository.findByOrderId(orderId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.info("Creating PaymentIntent for order {} from OrderPlaced event", orderId);
                    return doCreatePaymentIntent(orderId, total, currency, username, "payment-order-async-");
                }))
                .doOnNext(existing -> log.info("Payment already exists for order {}, skipping", orderId))
                .then();
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
     * <p><b>stripe_event is deliberately left alone.</b> It is the webhook
     * dedupe log — clearing it would let already-processed Stripe webhooks
     * replay against a payment that no longer exists.
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
        return new CreatePaymentIntentResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getStripePaymentIntentId(),
                payment.getClientSecret(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getCreatedAt(),
                refund);
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
                                        return publishPaymentRefundedEvent(existing);
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

        return provider.createRefund(payment.getStripePaymentIntentId(), amount, "refund-order-" + orderId)
                .flatMap(providerRefund -> {
                    // Unchanged from before the seam: a refund the provider accepted is
                    // recorded as SUCCEEDED regardless of the status string it came back
                    // with; a pending one is reconciled later by /sync.
                    refund.setStatus(RefundStatus.SUCCEEDED.name());
                    refund.setStripeRefundId(providerRefund.providerRefundId());
                    refund.setUpdatedAt(Instant.now());
                    refund.markNotNew();

                    payment.setStatus(PaymentStatus.REFUNDED.name());
                    payment.setUpdatedAt(Instant.now());
                    payment.markNotNew();

                    return refundRepository.save(refund)
                            .then(paymentRepository.save(payment))
                            .then(publishPaymentRefundedEvent(refund))
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

    private Mono<Void> publishPaymentRefundedEvent(Refund refund) {
        try {
            Map<String, Object> eventPayload = new LinkedHashMap<>();
            eventPayload.put("orderId", refund.getOrderId());
            eventPayload.put("status", PaymentStatus.REFUNDED.name());
            eventPayload.put("stripeRefundId", refund.getStripeRefundId());
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

    public Mono<Payment> createPaymentIntent(Long orderId, BigDecimal total, String currencyOverride, String username) {
        return paymentRepository.findByOrderId(orderId)
                .flatMap(existing -> {
                    log.info("Payment already exists for order {}, returning existing", orderId);
                    return Mono.just(existing);
                })
                .switchIfEmpty(Mono.defer(() -> doCreatePaymentIntent(orderId, total, currencyOverride, username, "payment-order-")));
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
                    var request = CreateIntentRequest.of(
                            orderId,
                            new Money(existing.getAmount(), existing.getCurrency()),
                            null,
                            "payment-retry-" + orderId + "-" + UUID.randomUUID());

                    return provider.recreateIntent(request, existing.getStripePaymentIntentId())
                            .flatMap(intent -> {
                                existing.setStripePaymentIntentId(intent.providerPaymentId());
                                existing.setClientSecret(intent.clientSecret());
                                existing.setStatus(PaymentStatus.CREATED.name());
                                existing.setUpdatedAt(Instant.now());
                                existing.markNotNew();
                                return paymentRepository.save(existing);
                            })
                            .flatMap(saved -> publishIntentCreatedEvent(saved).thenReturn(saved))
                            .doOnSuccess(saved -> log.info("Retried {} payment {} for order {}",
                                    provider.name(), saved.getStripePaymentIntentId(), orderId))
                            .onErrorResume(PaymentProviderException.class, e -> {
                                log.error("{} API error retrying payment for order {}: {}",
                                        provider.name(), orderId, e.getMessage(), e);
                                return Mono.error(e);
                            });
                });
    }

    public Mono<CreatePaymentIntentResponse> syncPaymentStatus(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .switchIfEmpty(Mono.error(new RuntimeException("Payment not found for order " + orderId)))
                .flatMap(payment -> {
                    String providerPaymentId = payment.getStripePaymentIntentId();
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
        return new CreatePaymentIntentResponse.RefundInfo(
                refund.getStripeRefundId(),
                refund.getAmount(),
                refund.getStatus(),
                refund.getCreatedAt());
    }

    private Mono<Void> reconcileRefund(Payment payment) {
        Long orderId = payment.getOrderId();
        return refundRepository.findByOrderId(orderId)
                .flatMap(refund -> {
                    if (RefundStatus.SUCCEEDED.name().equals(refund.getStatus())) {
                        return Mono.empty();
                    }
                    if (refund.getStripeRefundId() != null && !refund.getStripeRefundId().isBlank()) {
                        return syncRefundFromProvider(payment, refund);
                    }
                    // PENDING/FAILED without a Stripe refund id — the create call never completed,
                    // so re-attempt; the fixed idempotency key makes this safe.
                    log.info("Re-attempting refund for order {} via /sync (status {}, no Stripe refund id)",
                            orderId, refund.getStatus());
                    return executeRefund(payment, refund);
                });
    }

    private Mono<Void> syncRefundFromProvider(Payment payment, Refund refund) {
        return providerFor(payment).retrieveRefund(refund.getStripeRefundId())
                .flatMap(providerRefund -> {
                    RefundStatus newStatus = providerRefund.status();
                    if (newStatus == null || newStatus.name().equals(refund.getStatus())) {
                        return Mono.<Void>empty();
                    }
                    refund.setStatus(newStatus.name());
                    refund.setUpdatedAt(Instant.now());
                    refund.markNotNew();

                    Mono<Void> saveRefund = refundRepository.save(refund).then();
                    if (newStatus != RefundStatus.SUCCEEDED) {
                        return saveRefund;
                    }
                    payment.setStatus(PaymentStatus.REFUNDED.name());
                    payment.setUpdatedAt(Instant.now());
                    payment.markNotNew();
                    return saveRefund
                            .then(paymentRepository.save(payment))
                            .then(publishPaymentRefundedEvent(refund))
                            .doOnSuccess(v -> log.info("Refund {} for order {} reconciled to SUCCEEDED via /sync",
                                    refund.getStripeRefundId(), payment.getOrderId()));
                })
                .onErrorResume(PaymentProviderException.class, e -> {
                    log.error("Provider error retrieving refund {} for order {}: {}",
                            refund.getStripeRefundId(), payment.getOrderId(), e.getMessage(), e);
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

        payment.setStatus(mapped.name());
        payment.setUpdatedAt(Instant.now());
        payment.markNotNew();

        return paymentRepository.save(payment)
                .flatMap(saved -> publishStatusEvent(saved, mapped.name()).thenReturn(saved));
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
            String json = MAPPER.writeValueAsString(Map.of(
                    "orderId", payment.getOrderId(),
                    "stripePaymentIntentId", payment.getStripePaymentIntentId(),
                    "status", payment.getStatus()
            ));
            OutboxEvent outbox = new OutboxEvent(
                    "payment",
                    String.valueOf(payment.getOrderId()),
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
        Map<String, Object> eventPayload = Map.of(
                "orderId", saved.getOrderId(),
                "stripePaymentIntentId", saved.getStripePaymentIntentId(),
                "amount", saved.getAmount(),
                "currency", saved.getCurrency()
        );
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
        PaymentProvider provider = providerName == null ? providers.defaultProvider() : providers.get(providerName);

        // Username is passed as "" rather than null when unknown: create always sent the
        // metadata key before the seam, and the adapter omits it only for null.
        var request = CreateIntentRequest.of(
                orderId,
                new Money(total, cur),
                username != null ? username : "",
                idempotencyPrefix + orderId);

        return provider.createIntent(request)
                .flatMap(intent -> {
                    Payment payment = new Payment(orderId, total, cur.toUpperCase(), provider.name());
                    payment.setStripePaymentIntentId(intent.providerPaymentId());
                    payment.setClientSecret(intent.clientSecret());
                    payment.setStatus(PaymentStatus.CREATED.name());
                    payment.setCreatedAt(Instant.now());
                    payment.setUpdatedAt(Instant.now());

                    return paymentRepository.save(payment)
                            .flatMap(saved -> publishIntentCreatedEvent(saved).thenReturn(saved))
                            .doOnSuccess(saved -> log.info("Created {} payment {} for order {}",
                                    provider.name(), saved.getStripePaymentIntentId(), orderId));
                })
                .onErrorResume(PaymentProviderException.class, e -> {
                    log.error("{} API error creating payment for order {}: {}",
                            provider.name(), orderId, e.getMessage(), e);
                    return Mono.error(e);
                });
    }
}
