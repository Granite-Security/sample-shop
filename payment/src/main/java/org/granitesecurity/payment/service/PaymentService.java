package org.granitesecurity.payment.service;

import tools.jackson.databind.ObjectMapper;
import org.granitesecurity.payment.domain.OutboxEvent;
import org.granitesecurity.payment.domain.Payment;
import org.granitesecurity.payment.domain.PaymentStatus;
import org.granitesecurity.payment.repository.OutboxRepository;
import org.granitesecurity.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
                .switchIfEmpty(Mono.defer(() -> createPaymentAndOutboxEvent(orderId, total, username)))
                .then();
    }

    private Mono<Void> createPaymentAndOutboxEvent(Long orderId, BigDecimal total, String username) {
        Payment payment = new Payment(orderId, total, "USD", "stripe");
        payment.setStatus(PaymentStatus.SUCCEEDED.name());
        payment.setCreatedAt(Instant.now());
        payment.setUpdatedAt(Instant.now());

        return paymentRepository.save(payment)
                .flatMap(saved -> {
                    Map<String, Object> eventPayload = Map.of(
                            "orderId", saved.getOrderId(),
                            "paymentId", saved.getId().toString(),
                            "amount", saved.getAmount(),
                            "paidAt", Instant.now().toString()
                    );
                    try {
                        String json = MAPPER.writeValueAsString(eventPayload);
                        OutboxEvent outboxEvent = new OutboxEvent(
                                "payment",
                                String.valueOf(saved.getOrderId()),
                                "PaymentReceived",
                                json,
                                "PENDING"
                        );
                        return outboxRepository.save(outboxEvent).then();
                    } catch (Exception e) {
                        log.error("Failed to serialize payment event payload", e);
                        return Mono.error(e);
                    }
                })
                .doOnSuccess(v -> log.info("Created payment and outbox event for order {}", orderId));
    }
}
