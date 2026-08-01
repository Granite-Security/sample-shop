package org.granitesecurity.payment.service;

import com.stripe.exception.IdempotencyException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentSearchParams;
import com.stripe.param.RefundCreateParams;
import tools.jackson.databind.ObjectMapper;
import org.granitesecurity.payment.domain.OutboxEvent;
import org.granitesecurity.payment.domain.Payment;
import org.granitesecurity.payment.domain.PaymentStatus;
import org.granitesecurity.payment.domain.Refund;
import org.granitesecurity.payment.domain.RefundStatus;
import org.granitesecurity.payment.dto.CreatePaymentIntentResponse;
import org.granitesecurity.payment.repository.OutboxRepository;
import org.granitesecurity.payment.repository.PaymentRepository;
import org.granitesecurity.payment.repository.RefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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

    @Value("${stripe.currency:usd}")
    private String currency;

    public PaymentService(PaymentRepository paymentRepository, OutboxRepository outboxRepository, RefundRepository refundRepository) {
        this.paymentRepository = paymentRepository;
        this.outboxRepository = outboxRepository;
        this.refundRepository = refundRepository;
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
        long amountCents = MinorUnits.toMinorUnits(payment.getAmount(), payment.getCurrency());

        var params = RefundCreateParams.builder()
                .setPaymentIntent(payment.getStripePaymentIntentId())
                .setAmount(amountCents)
                .build();

        var options = RequestOptions.builder()
                .setIdempotencyKey("refund-order-" + orderId)
                .build();

        return Mono.fromCallable(() -> com.stripe.model.Refund.create(params, options))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(stripeRefund -> {
                    refund.setStatus(RefundStatus.SUCCEEDED.name());
                    refund.setStripeRefundId(stripeRefund.getId());
                    refund.setUpdatedAt(Instant.now());
                    refund.markNotNew();

                    payment.setStatus(PaymentStatus.REFUNDED.name());
                    payment.setUpdatedAt(Instant.now());
                    payment.markNotNew();

                    return refundRepository.save(refund)
                            .then(paymentRepository.save(payment))
                            .then(publishPaymentRefundedEvent(refund))
                            .doOnSuccess(v -> log.info("Refunded order {} via Stripe refund {}", orderId, stripeRefund.getId()));
                })
                .onErrorResume(StripeException.class, e -> {
                    log.error("Stripe API error refunding order {}: {}", orderId, e.getMessage(), e);
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

                    long amountCents = MinorUnits.toMinorUnits(existing.getAmount(), existing.getCurrency());
                    var params = PaymentIntentCreateParams.builder()
                            .setAmount(amountCents)
                            .setCurrency(existing.getCurrency().toLowerCase())
                            .setAutomaticPaymentMethods(
                                    PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                            .setEnabled(true)
                                            .build()
                            )
                            .putMetadata("order_id", orderId.toString())
                            .build();

                    var options = RequestOptions.builder()
                            .setIdempotencyKey("payment-retry-" + orderId + "-" + UUID.randomUUID())
                            .build();

                    return Mono.fromCallable(() -> PaymentIntent.create(params, options))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(intent -> {
                                existing.setStripePaymentIntentId(intent.getId());
                                existing.setClientSecret(intent.getClientSecret());
                                existing.setStatus(PaymentStatus.CREATED.name());
                                existing.setUpdatedAt(Instant.now());
                                existing.markNotNew();
                                return paymentRepository.save(existing);
                            })
                            .flatMap(saved -> publishIntentCreatedEvent(saved).thenReturn(saved))
                            .doOnSuccess(saved -> log.info("Retried Stripe PaymentIntent {} for order {}",
                                    saved.getStripePaymentIntentId(), orderId))
                            .onErrorResume(StripeException.class, e -> {
                                log.error("Stripe API error retrying PaymentIntent for order {}: {}",
                                        orderId, e.getMessage(), e);
                                return Mono.error(e);
                            });
                });
    }

    public Mono<CreatePaymentIntentResponse> syncPaymentStatus(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .switchIfEmpty(Mono.error(new RuntimeException("Payment not found for order " + orderId)))
                .flatMap(payment -> {
                    String stripePiId = payment.getStripePaymentIntentId();
                    if (stripePiId == null) {
                        return Mono.error(new RuntimeException("No Stripe PaymentIntent for order " + orderId));
                    }
                    return Mono.fromCallable(() -> PaymentIntent.retrieve(stripePiId))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(intent -> updateFromStripeStatus(payment, intent.getStatus()));
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
                        return syncRefundFromStripe(payment, refund);
                    }
                    // PENDING/FAILED without a Stripe refund id — the create call never completed,
                    // so re-attempt; the fixed idempotency key makes this safe.
                    log.info("Re-attempting refund for order {} via /sync (status {}, no Stripe refund id)",
                            orderId, refund.getStatus());
                    return executeRefund(payment, refund);
                });
    }

    private Mono<Void> syncRefundFromStripe(Payment payment, Refund refund) {
        return Mono.fromCallable(() -> com.stripe.model.Refund.retrieve(refund.getStripeRefundId()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(stripeRefund -> {
                    RefundStatus newStatus = mapStripeRefundStatus(stripeRefund.getStatus());
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
                .onErrorResume(StripeException.class, e -> {
                    log.error("Stripe API error retrieving refund {} for order {}: {}",
                            refund.getStripeRefundId(), payment.getOrderId(), e.getMessage(), e);
                    return Mono.empty();
                });
    }

    private static RefundStatus mapStripeRefundStatus(String stripeStatus) {
        return switch (stripeStatus) {
            case "succeeded" -> RefundStatus.SUCCEEDED;
            case "failed", "canceled" -> RefundStatus.FAILED;
            case "pending" -> RefundStatus.PENDING;
            default -> null;
        };
    }

    private Mono<Payment> updateFromStripeStatus(Payment payment, String stripeStatus) {
        String newStatus = mapStripeStatus(stripeStatus);
        if (newStatus == null || newStatus.equals(payment.getStatus())) {
            return Mono.just(payment);
        }

        payment.setStatus(newStatus);
        payment.setUpdatedAt(Instant.now());
        payment.markNotNew();

        return paymentRepository.save(payment)
                .flatMap(saved -> publishStatusEvent(saved, newStatus).thenReturn(saved));
    }

    private static String mapStripeStatus(String stripeStatus) {
        return switch (stripeStatus) {
            case "succeeded" -> PaymentStatus.SUCCEEDED.name();
            case "canceled" -> PaymentStatus.CANCELED.name();
            case "processing" -> PaymentStatus.PROCESSING.name();
            default -> null;
        };
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
        String cur = currencyOverride != null ? currencyOverride : currency;
        long amountCents = MinorUnits.toMinorUnits(total, cur);

        var params = PaymentIntentCreateParams.builder()
                .setAmount(amountCents)
                .setCurrency(cur)
                .setAutomaticPaymentMethods(
                        PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                .setEnabled(true)
                                .build()
                )
                .putMetadata("order_id", orderId.toString())
                .putMetadata("username", username != null ? username : "")
                .build();

        var options = RequestOptions.builder()
                .setIdempotencyKey(idempotencyPrefix + orderId)
                .build();

        return Mono.fromCallable(() -> PaymentIntent.create(params, options))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(IdempotencyException.class, e -> {
                    log.warn("Idempotency key collision for order {}, searching for existing PaymentIntent", orderId);
                    return Mono.fromCallable(() -> {
                        var searchParams = PaymentIntentSearchParams.builder()
                                .setQuery("metadata['order_id']:'" + orderId + "'")
                                .setLimit(1L)
                                .build();
                        return PaymentIntent.search(searchParams).getData().stream()
                                .findFirst()
                                .orElseThrow(() -> e);
                    }).subscribeOn(Schedulers.boundedElastic());
                })
                .flatMap(intent -> {
                    Payment payment = new Payment(orderId, total, cur.toUpperCase(), "stripe");
                    payment.setStripePaymentIntentId(intent.getId());
                    payment.setClientSecret(intent.getClientSecret());
                    payment.setStatus(PaymentStatus.CREATED.name());
                    payment.setCreatedAt(Instant.now());
                    payment.setUpdatedAt(Instant.now());

                    return paymentRepository.save(payment)
                            .flatMap(saved -> publishIntentCreatedEvent(saved).thenReturn(saved))
                            .doOnSuccess(saved -> log.info("Created Stripe PaymentIntent {} for order {}",
                                    saved.getStripePaymentIntentId(), orderId));
                })
                .onErrorResume(StripeException.class, e -> {
                    log.error("Stripe API error creating PaymentIntent for order {}: {}",
                            orderId, e.getMessage(), e);
                    return Mono.error(e);
                });
    }
}
