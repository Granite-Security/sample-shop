package org.granitesecurity.payment.service;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
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
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

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

    @Transactional
    public Mono<Void> processOrderPlaced(Long orderId, BigDecimal total, String username) {
        return paymentRepository.findByOrderId(orderId)
                .flatMap(existing -> {
                    log.info("Payment already exists for order {}, skipping", orderId);
                    return Mono.empty();
                })
                .switchIfEmpty(Mono.defer(() -> createStripePaymentIntent(orderId, total, username)))
                .then();
    }

    private Mono<Void> createStripePaymentIntent(Long orderId, BigDecimal total, String username) {
        long amountCents = total.multiply(BigDecimal.valueOf(100)).longValue();

        var params = PaymentIntentCreateParams.builder()
                .setAmount(amountCents)
                .setCurrency(currency)
                .setAutomaticPaymentMethods(
                        PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                .setEnabled(true)
                                .build()
                )
                .putMetadata("order_id", orderId.toString())
                .putMetadata("username", username != null ? username : "")
                .build();

        var options = RequestOptions.builder()
                .setIdempotencyKey("payment-order-" + orderId)
                .build();

        return Mono.fromCallable(() -> PaymentIntent.create(params, options))
                .flatMap(intent -> {
                    Payment payment = new Payment(orderId, total, currency.toUpperCase(), "stripe");
                    payment.setStripePaymentIntentId(intent.getId());
                    payment.setClientSecret(intent.getClientSecret());
                    payment.setStatus(PaymentStatus.CREATED.name());
                    payment.setCreatedAt(Instant.now());
                    payment.setUpdatedAt(Instant.now());

                    return paymentRepository.save(payment)
                            .flatMap(saved -> {
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
                            })
                            .doOnSuccess(v -> log.info("Created Stripe PaymentIntent {} for order {}",
                                    intent.getId(), orderId));
                })
                .onErrorResume(StripeException.class, e -> {
                    log.error("Stripe API error creating PaymentIntent for order {}: {}",
                            orderId, e.getMessage(), e);
                    return Mono.error(e);
                });
    }
}
