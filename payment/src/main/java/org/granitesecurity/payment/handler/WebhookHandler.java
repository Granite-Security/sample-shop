package org.granitesecurity.payment.handler;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import tools.jackson.databind.ObjectMapper;
import org.granitesecurity.payment.domain.OutboxEvent;
import org.granitesecurity.payment.domain.Payment;
import org.granitesecurity.payment.domain.PaymentStatus;
import org.granitesecurity.payment.domain.StripeEvent;
import org.granitesecurity.payment.repository.OutboxRepository;
import org.granitesecurity.payment.repository.PaymentRepository;
import org.granitesecurity.payment.repository.StripeEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

@Service
public class WebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(WebhookHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PaymentRepository paymentRepository;
    private final StripeEventRepository stripeEventRepository;
    private final OutboxRepository outboxRepository;

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    public WebhookHandler(PaymentRepository paymentRepository,
                          StripeEventRepository stripeEventRepository,
                          OutboxRepository outboxRepository) {
        this.paymentRepository = paymentRepository;
        this.stripeEventRepository = stripeEventRepository;
        this.outboxRepository = outboxRepository;
    }

    public Mono<ServerResponse> handleWebhook(ServerRequest request) {
        log.info("Received Stripe webhook request: {}", request);
        String sigHeader = request.headers().firstHeader("Stripe-Signature");
        log.info("Received Stripe webhook with signature: {}", sigHeader);
        if (sigHeader == null || sigHeader.isBlank()) {
            return ServerResponse.badRequest().bodyValue(Map.of("error", "Missing Stripe-Signature header"));
        }

        return request.bodyToMono(String.class)
                .flatMap(payload -> {
                    try {
                        Event event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
                        return processEvent(event, payload);
                    } catch (SignatureVerificationException e) {
                        log.warn("Webhook signature verification failed: {}", e.getMessage());
                        return ServerResponse.badRequest().bodyValue(Map.of("error", "Invalid signature"));
                    }
                });
    }

    private Mono<ServerResponse> processEvent(Event event, String payload) {
        return stripeEventRepository.findByStripeEventId(event.getId())
                .flatMap(existing -> {
                    log.info("Duplicate webhook event {} ({}) — skipping", event.getId(), event.getType());
                    return ServerResponse.ok().bodyValue(Map.of("status", "duplicate"));
                })
                .switchIfEmpty(Mono.defer(() -> handleEvent(event, payload)));
    }

    @SuppressWarnings("unchecked")
    private Mono<ServerResponse> handleEvent(Event event, String payload) {
        log.info("Recording webhook event {} ({})", event.getId(), event.getType());
        return recordEvent(event)
                .flatMap(se -> {
                    log.info("Recorded event {} as {}", event.getId(), se.getId());
                    return processPayload(event, payload);
                })
                .onErrorResume(e -> {
                    log.error("Webhook processing error for event {}: {}", event.getId(), e.getMessage());
                    return ServerResponse.status(500).bodyValue(Map.of("error", e.getMessage()));
                });
    }

    private Mono<ServerResponse> processPayload(Event event, String payload) {
        if (!isStatusChangeEvent(event.getType())) {
            log.info("Ignoring unhandled event type: {} ({})", event.getType(), event.getId());
            return ServerResponse.ok().bodyValue(Map.of("status", "skipped", "reason", "unhandled_event_type"));
        }

        var deserializer = event.getDataObjectDeserializer();
        var objectOpt = deserializer.getObject();

        if (objectOpt.isPresent() && objectOpt.get() instanceof PaymentIntent intent) {
            return processPaymentIntent(event, intent);
        }

        log.warn("SDK deserializer failed for event {} ({}), falling back to manual JSON parsing", event.getId(), event.getType());
        try {
            var node = MAPPER.readTree(payload);
            var dataObj = node.get("data").get("object");
            String piId = dataObj.get("id").asText();
            var meta = dataObj.get("metadata");
            String orderIdStr = meta != null ? meta.get("order_id").asText(null) : null;

            if (orderIdStr == null || orderIdStr.isEmpty()) {
                log.warn("PaymentIntent {} has no order_id metadata", piId);
                return ServerResponse.ok().bodyValue(Map.of("status", "skipped", "reason", "no_order_id"));
            }

            Long orderId = Long.parseLong(orderIdStr);
            return paymentRepository.findByOrderId(orderId)
                    .switchIfEmpty(Mono.error(new RuntimeException("Payment not found for order " + orderId)))
                    .flatMap(payment -> updatePaymentStatus(payment, event.getType())
                            .switchIfEmpty(Mono.just(payment)))
                    .flatMap(payment -> publishStatusEvent(payment, event.getType())
                            .thenReturn(payment)
                            .defaultIfEmpty(payment))
                    .flatMap(payment -> ServerResponse.ok().bodyValue(Map.of(
                            "status", "processed",
                            "orderId", payment.getOrderId(),
                            "paymentStatus", payment.getStatus())));
        } catch (Exception e) {
            log.error("Manual JSON parsing failed for event {}: {}", event.getId(), e.getMessage());
            return ServerResponse.ok().bodyValue(Map.of("status", "skipped", "reason", "manual_parsing_failed"));
        }
    }

    private Mono<ServerResponse> processPaymentIntent(Event event, PaymentIntent intent) {

        String orderIdStr = intent.getMetadata().get("order_id");
        if (orderIdStr == null) {
            log.warn("PaymentIntent {} has no order_id metadata", intent.getId());
            return ServerResponse.ok().bodyValue(Map.of("status", "skipped", "reason", "no_order_id"));
        }

        Long orderId = Long.parseLong(orderIdStr);

        return paymentRepository.findByOrderId(orderId)
                .switchIfEmpty(Mono.error(new RuntimeException("Payment not found for order " + orderId)))
                .flatMap(payment -> updatePaymentStatus(payment, event.getType())
                        .switchIfEmpty(Mono.just(payment)))
                .flatMap(payment -> publishStatusEvent(payment, event.getType())
                        .thenReturn(payment)
                        .defaultIfEmpty(payment))
                .flatMap(payment -> ServerResponse.ok().bodyValue(Map.of(
                        "status", "processed",
                        "orderId", payment.getOrderId(),
                        "paymentStatus", payment.getStatus())));
    }

    private Mono<Payment> updatePaymentStatus(Payment payment, String eventType) {
        String newStatus = switch (eventType) {
            case "payment_intent.succeeded" -> PaymentStatus.SUCCEEDED.name();
            case "payment_intent.payment_failed" -> PaymentStatus.FAILED.name();
            case "payment_intent.canceled" -> PaymentStatus.CANCELED.name();
            default -> null;
        };
        if (newStatus == null) {
            log.info("Unhandled event type: {}", eventType);
            return Mono.empty();
        }
        payment.setStatus(newStatus);
        payment.setUpdatedAt(Instant.now());
        return paymentRepository.save(payment);
    }

    private Mono<Void> publishStatusEvent(Payment payment, String eventType) {
        String eventName = switch (eventType) {
            case "payment_intent.succeeded" -> "PaymentSucceeded";
            case "payment_intent.payment_failed" -> "PaymentFailed";
            case "payment_intent.canceled" -> "PaymentCanceled";
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

    private static boolean isStatusChangeEvent(String eventType) {
        return "payment_intent.succeeded".equals(eventType)
                || "payment_intent.payment_failed".equals(eventType)
                || "payment_intent.canceled".equals(eventType);
    }

    private Mono<StripeEvent> recordEvent(Event event) {
        StripeEvent se = new StripeEvent(event.getId(), event.getType());
        se.setProcessedAt(Instant.now());
        return stripeEventRepository.save(se);
    }
}
