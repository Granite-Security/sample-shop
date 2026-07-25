package org.granitesecurity.payment.service;

import com.stripe.exception.IdempotencyException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentSearchParams;
import tools.jackson.databind.ObjectMapper;
import org.granitesecurity.payment.domain.OutboxEvent;
import org.granitesecurity.payment.domain.Payment;
import org.granitesecurity.payment.domain.PaymentStatus;
import org.granitesecurity.payment.repository.OutboxRepository;
import org.granitesecurity.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PaymentRepository paymentRepository;
    private final OutboxRepository outboxRepository;

    @Value("${stripe.currency:usd}")
    private String currency;

    public PaymentService(PaymentRepository paymentRepository, OutboxRepository outboxRepository) {
        this.paymentRepository = paymentRepository;
        this.outboxRepository = outboxRepository;
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

                    long amountCents = existing.getAmount().multiply(BigDecimal.valueOf(100)).longValue();
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

    public Mono<Payment> syncPaymentStatus(Long orderId) {
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
                });
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
        Map<String, Object> eventPayload = Map.of(
                "orderId", saved.getOrderId(),
                "stripePaymentIntentId", saved.getStripePaymentIntentId(),
                "clientSecret", saved.getClientSecret(),
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
        long amountCents = total.multiply(BigDecimal.valueOf(100)).longValue();
        String cur = currencyOverride != null ? currencyOverride : currency;

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
