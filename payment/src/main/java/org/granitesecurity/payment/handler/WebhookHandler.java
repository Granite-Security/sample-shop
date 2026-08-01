package org.granitesecurity.payment.handler;

import tools.jackson.databind.ObjectMapper;
import org.granitesecurity.payment.domain.OutboxEvent;
import org.granitesecurity.payment.domain.Payment;
import org.granitesecurity.payment.domain.PaymentStatus;
import org.granitesecurity.payment.domain.ProviderEvent;
import org.granitesecurity.payment.provider.PaymentProvider;
import org.granitesecurity.payment.provider.PaymentProviderRegistry;
import org.granitesecurity.payment.provider.ProviderWebhookEvent;
import org.granitesecurity.payment.provider.WebhookVerificationException;
import org.granitesecurity.payment.repository.OutboxRepository;
import org.granitesecurity.payment.repository.PaymentRepository;
import org.granitesecurity.payment.repository.ProviderEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Provider-scoped webhook intake: {@code POST /api/payments/webhook/{provider}}.
 *
 * <p>This class knows nothing about any provider's event shape. Verification and
 * translation happen in the adapter's {@code parseWebhook}; what arrives here is a
 * {@link ProviderWebhookEvent} with the order id and mapped status already resolved.
 */
@Service
public class WebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(WebhookHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PaymentRepository paymentRepository;
    private final ProviderEventRepository providerEventRepository;
    private final OutboxRepository outboxRepository;
    private final PaymentProviderRegistry providers;

    public WebhookHandler(PaymentRepository paymentRepository,
                          ProviderEventRepository providerEventRepository,
                          OutboxRepository outboxRepository,
                          PaymentProviderRegistry providers) {
        this.paymentRepository = paymentRepository;
        this.providerEventRepository = providerEventRepository;
        this.outboxRepository = outboxRepository;
        this.providers = providers;
    }

    public Mono<ServerResponse> handleWebhook(ServerRequest request) {
        String providerName = request.pathVariable("provider");
        if (!providers.has(providerName)) {
            log.warn("Webhook for unknown provider '{}'", providerName);
            return ServerResponse.notFound().build();
        }
        PaymentProvider provider = providers.get(providerName);

        // A delivery arriving for a provider whose webhook we never registered is a
        // misconfiguration worth surfacing loudly rather than silently processing:
        // the signature secret is likely unset, so verification would be meaningless.
        if (!provider.webhookEnabled()) {
            log.error("Webhook received for '{}' but webhooks are disabled for it — refusing", providerName);
            return ServerResponse.status(503).bodyValue(Map.of(
                    "error", "Webhooks are not enabled for provider " + providerName));
        }

        Map<String, String> headers = new HashMap<>();
        request.headers().asHttpHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.getFirst());
            }
        });

        return request.bodyToMono(String.class)
                .flatMap(payload -> {
                    ProviderWebhookEvent event;
                    try {
                        event = provider.parseWebhook(payload, headers);
                    } catch (WebhookVerificationException e) {
                        log.warn("Webhook signature verification failed for {}: {}", providerName, e.getMessage());
                        return ServerResponse.badRequest().bodyValue(Map.of("error", e.getMessage()));
                    }
                    return processEvent(provider, event);
                });
    }

    private Mono<ServerResponse> processEvent(PaymentProvider provider, ProviderWebhookEvent event) {
        return providerEventRepository.findByProviderAndProviderEventId(provider.name(), event.eventId())
                .flatMap(existing -> {
                    log.info("Duplicate webhook event {} ({}) — skipping", event.eventId(), event.eventType());
                    return ServerResponse.ok().bodyValue(Map.of("status", "duplicate"));
                })
                .switchIfEmpty(Mono.defer(() -> recordAndApply(provider, event)));
    }

    private Mono<ServerResponse> recordAndApply(PaymentProvider provider, ProviderWebhookEvent event) {
        log.info("Recording webhook event {} ({}) from {}", event.eventId(), event.eventType(), provider.name());
        return recordEvent(provider, event)
                .flatMap(se -> apply(event))
                .onErrorResume(e -> {
                    log.error("Webhook processing error for event {}: {}", event.eventId(), e.getMessage());
                    return ServerResponse.status(500).bodyValue(Map.of("error", String.valueOf(e.getMessage())));
                });
    }

    private Mono<ServerResponse> apply(ProviderWebhookEvent event) {
        if (event.status() == null) {
            log.info("Ignoring unhandled event type: {} ({})", event.eventType(), event.eventId());
            return ServerResponse.ok().bodyValue(Map.of("status", "skipped", "reason", "unhandled_event_type"));
        }
        if (event.orderId() == null) {
            return ServerResponse.ok().bodyValue(Map.of("status", "skipped", "reason", "no_order_id"));
        }

        return paymentRepository.findByOrderId(event.orderId())
                .switchIfEmpty(Mono.error(new RuntimeException("Payment not found for order " + event.orderId())))
                .flatMap(payment -> updatePaymentStatus(payment, event.status()))
                .flatMap(payment -> publishStatusEvent(payment, event.status()).thenReturn(payment))
                .flatMap(payment -> ServerResponse.ok().bodyValue(Map.of(
                        "status", "processed",
                        "orderId", payment.getOrderId(),
                        "paymentStatus", payment.getStatus())));
    }

    private Mono<Payment> updatePaymentStatus(Payment payment, PaymentStatus newStatus) {
        payment.setStatus(newStatus.name());
        payment.setUpdatedAt(Instant.now());
        payment.markNotNew();
        return paymentRepository.save(payment);
    }

    private Mono<Void> publishStatusEvent(Payment payment, PaymentStatus status) {
        String eventName = switch (status) {
            case SUCCEEDED -> "PaymentSucceeded";
            case FAILED -> "PaymentFailed";
            case CANCELED -> "PaymentCanceled";
            default -> null;
        };
        if (eventName == null) return Mono.empty();

        try {
            String json = MAPPER.writeValueAsString(Map.of(
                    "orderId", payment.getOrderId(),
                    "stripePaymentIntentId", payment.getProviderPaymentId(),
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

    private Mono<ProviderEvent> recordEvent(PaymentProvider provider, ProviderWebhookEvent event) {
        ProviderEvent recorded = new ProviderEvent(provider.name(), event.eventId(), event.eventType());
        recorded.setProcessedAt(Instant.now());
        return providerEventRepository.save(recorded);
    }
}
