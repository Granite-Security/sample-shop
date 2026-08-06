package org.granitesecurity.payment.handler;

import tools.jackson.databind.ObjectMapper;
import org.granitesecurity.payment.domain.OutboxEvent;
import org.granitesecurity.payment.domain.Payment;
import org.granitesecurity.payment.domain.PaymentStatus;
import org.granitesecurity.payment.domain.ProviderEvent;
import org.granitesecurity.payment.domain.Refund;
import org.granitesecurity.payment.domain.RefundStatus;
import org.granitesecurity.payment.provider.PaymentProvider;
import org.granitesecurity.payment.provider.PaymentProviderRegistry;
import org.granitesecurity.payment.provider.ProviderWebhookEvent;
import org.granitesecurity.payment.provider.WebhookVerificationException;
import org.granitesecurity.payment.repository.OutboxRepository;
import org.granitesecurity.payment.repository.PaymentRepository;
import org.granitesecurity.payment.repository.RefundRepository;
import org.granitesecurity.payment.repository.ProviderEventRepository;
import org.granitesecurity.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private final RefundRepository refundRepository;
    private final PaymentProviderRegistry providers;
    private final PaymentService paymentService;

    public WebhookHandler(PaymentRepository paymentRepository,
                          ProviderEventRepository providerEventRepository,
                          OutboxRepository outboxRepository,
                          RefundRepository refundRepository,
                          PaymentProviderRegistry providers,
                          PaymentService paymentService) {
        this.paymentRepository = paymentRepository;
        this.providerEventRepository = providerEventRepository;
        this.outboxRepository = outboxRepository;
        this.refundRepository = refundRepository;
        this.providers = providers;
        this.paymentService = paymentService;
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
                .flatMap(payload -> provider.parseWebhook(payload, headers)
                        .flatMap(event -> processEvent(provider, event))
                        // Verification is now a Mono because some providers verify over
                        // the network, so a failed signature arrives as an error signal
                        // rather than a thrown exception.
                        .onErrorResume(WebhookVerificationException.class, e -> {
                            log.warn("Webhook signature verification failed for {}: {}",
                                    providerName, e.getMessage());
                            return ServerResponse.badRequest()
                                    .bodyValue(Map.of("error", String.valueOf(e.getMessage())));
                        }));
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
                .flatMap(se -> apply(provider, event))
                .onErrorResume(e -> {
                    log.error("Webhook processing error for event {}: {}", event.eventId(), e.getMessage());
                    return ServerResponse.status(500).bodyValue(Map.of("error", String.valueOf(e.getMessage())));
                });
    }

    private Mono<ServerResponse> apply(PaymentProvider provider, ProviderWebhookEvent event) {
        if (event.isRefundTransition()) {
            return applyRefund(event);
        }
        if (event.requiresFinalization()) {
            return applyFinalization(provider, event);
        }
        if (event.status() == null) {
            log.info("Ignoring unhandled event type: {} ({})", event.eventType(), event.eventId());
            return ServerResponse.ok().bodyValue(Map.of("status", "skipped", "reason", "unhandled_event_type"));
        }
        // Nothing to look the payment up by. Previously this was the "no order id"
        // case; it now also covers a reference we did not write. Skipped with a 200
        // on purpose: an event for a payment created outside this system (straight
        // from a provider dashboard) is not ours, and erroring would have the
        // provider retry it forever.
        if (event.orderId() == null && paymentId(event) == null) {
            log.info("Event {} ({}) carries nothing we can resolve — skipping",
                    event.eventId(), event.eventType());
            return ServerResponse.ok().bodyValue(Map.of("status", "skipped", "reason", "unresolvable"));
        }
        return resolvePayment(event)
                .switchIfEmpty(Mono.error(new RuntimeException("Payment not found for " + describe(event))))
                .flatMap(payment -> updatePaymentStatus(payment, event.status()))
                .flatMap(payment -> publishStatusEvent(payment, event.status()).thenReturn(payment))
                .flatMap(payment -> ServerResponse.ok().bodyValue(Map.of(
                        "status", "processed",
                        "reference", describe(event),
                        "paymentStatus", payment.getStatus())));
    }

    /**
     * The shopper approved but the money is still sitting there. Take it.
     *
     * <p>This is the path that saves the shopper who approves and then closes the tab:
     * they never hit the return URL, so without this the order stays unpaid forever
     * while PayPal holds an approved order. It races the return endpoint by design —
     * {@code finalizePayment} is required to be idempotent for exactly this reason.
     */
    private Mono<ServerResponse> applyFinalization(PaymentProvider provider, ProviderWebhookEvent event) {
        log.info("Event {} ({}) reports {} approved — finalizing",
                event.eventId(), event.eventType(), describe(event));

        Mono<Payment> finalized;
        if (event.orderId() != null) {
            finalized = paymentService.finalizeRedirectPayment(provider.name(), event.orderId());
        } else if (event.isOrderless() && paymentId(event) != null) {
            // A top-up: no order to key on, so it finalizes by payment id. Without
            // this the shopper who approves and closes the tab is charged and never
            // credited (docs/finance/finance.md §6.1).
            finalized = paymentService.finalizeRedirectTopup(provider.name(), paymentId(event));
        } else {
            return ServerResponse.ok().bodyValue(Map.of("status", "skipped", "reason", "unresolvable"));
        }

        return finalized.flatMap(payment -> ServerResponse.ok().bodyValue(Map.of(
                "status", "processed",
                "reference", describe(event),
                "paymentStatus", payment.getStatus())));
    }

    /**
     * Finds the payment an event belongs to.
     *
     * <p>An order payment resolves by order id, as it always has. A top-up has no
     * order, so it resolves by the payment id we put in the provider's metadata as
     * `reference`. Before this existed, such an event was skipped with a 200 — the
     * provider never retried, and the money was captured but never credited.
     */
    private Mono<Payment> resolvePayment(ProviderWebhookEvent event) {
        if (event.orderId() != null) {
            return paymentRepository.findByOrderId(event.orderId());
        }
        java.util.UUID id = paymentId(event);
        return id == null ? Mono.empty() : paymentRepository.findById(id);
    }

    /** The reference as a payment id, or null when it is not one (an order id, say). */
    private static java.util.UUID paymentId(ProviderWebhookEvent event) {
        if (event.reference() == null) {
            return null;
        }
        try {
            return java.util.UUID.fromString(event.reference());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String describe(ProviderWebhookEvent event) {
        return event.orderId() != null ? "order " + event.orderId() : "payment " + event.reference();
    }

    /**
     * Applies a refund status the provider reported.
     *
     * <p>This is the only signal that arrives after we have stopped looking: a refund
     * is recorded SUCCEEDED the moment the provider accepts it, and can still fail
     * afterwards at the bank. Without this, the row would keep claiming SUCCEEDED
     * while the shopper never got their money.
     */
    private Mono<ServerResponse> applyRefund(ProviderWebhookEvent event) {
        return refundRepository.findByProviderRefundId(event.providerRefundId())
                .switchIfEmpty(Mono.defer(() -> {
                    // A refund we never recorded — issued straight from the Stripe
                    // dashboard, say. Nothing to correct, and inventing a row here
                    // would fabricate an order we know nothing about.
                    log.warn("Refund event {} for unknown refund {} — skipping",
                            event.eventId(), event.providerRefundId());
                    return Mono.empty();
                }))
                .flatMap(refund -> {
                    RefundStatus incoming = event.refundStatus();
                    if (incoming.name().equals(refund.getStatus())) {
                        return Mono.just(refund);
                    }
                    refund.setStatus(incoming.name());
                    refund.setUpdatedAt(Instant.now());
                    refund.markNotNew();
                    return refundRepository.save(refund)
                            .flatMap(saved -> incoming == RefundStatus.FAILED
                                    ? unwindFailedRefund(saved).thenReturn(saved)
                                    : Mono.just(saved));
                })
                .flatMap(refund -> ServerResponse.ok().bodyValue(Map.of(
                        "status", "processed",
                        "refundId", String.valueOf(refund.getProviderRefundId()),
                        "refundStatus", refund.getStatus())))
                .switchIfEmpty(ServerResponse.ok().bodyValue(
                        Map.of("status", "skipped", "reason", "unknown_refund")));
    }

    /**
     * The money did not go back. Restore the payment to SUCCEEDED — we are still
     * holding it — and tell shop, which has the order sitting in REIMBURSED on the
     * strength of a refund that never completed.
     */
    private Mono<Void> unwindFailedRefund(Refund refund) {
        log.error("Refund {} for order {} FAILED after being recorded as succeeded — unwinding",
                refund.getProviderRefundId(), refund.getOrderId());
        return paymentRepository.findByOrderId(refund.getOrderId())
                .flatMap(payment -> {
                    payment.setStatus(PaymentStatus.SUCCEEDED.name());
                    payment.setUpdatedAt(Instant.now());
                    payment.markNotNew();
                    return paymentRepository.save(payment);
                })
                .then(publishRefundFailedEvent(refund));
    }

    private Mono<Void> publishRefundFailedEvent(Refund refund) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("orderId", refund.getOrderId());
            // Deliberately not "FAILED": shop's consumer switches on this value and
            // already maps FAILED to PAYMENT_FAILED, which would mark the order as
            // never paid rather than walking the refund back.
            payload.put("status", "REFUND_FAILED");
            payload.put("providerRefundId", refund.getProviderRefundId());
            payload.put("amount", refund.getAmount());
            payload.put("failedAt", Instant.now());
            OutboxEvent outbox = new OutboxEvent(
                    "payment",
                    String.valueOf(refund.getOrderId()),
                    "PaymentRefundFailed",
                    MAPPER.writeValueAsString(payload),
                    "PENDING");
            return outboxRepository.save(outbox).then();
        } catch (Exception e) {
            log.error("Failed to serialize PaymentRefundFailed event", e);
            return Mono.error(e);
        }
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
            Map<String, Object> eventPayload = new LinkedHashMap<>();
            eventPayload.put("orderId", payment.getOrderId());
            eventPayload.put("provider", payment.getProvider());
            eventPayload.put("providerPaymentId", payment.getProviderPaymentId());
            // Legacy alias, kept alongside for in-flight messages during a rollout.
            eventPayload.put("stripePaymentIntentId", payment.getProviderPaymentId());
            eventPayload.put("status", payment.getStatus());
            String json = MAPPER.writeValueAsString(eventPayload);
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
