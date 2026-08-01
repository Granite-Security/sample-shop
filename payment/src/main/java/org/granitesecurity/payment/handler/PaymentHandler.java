package org.granitesecurity.payment.handler;

import org.granitesecurity.payment.dto.CreatePaymentIntentRequest;
import org.granitesecurity.payment.provider.PaymentProviderRegistry;
import org.granitesecurity.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Service
public class PaymentHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentHandler.class);

    private final PaymentService paymentService;
    private final PaymentProviderRegistry providers;

    public PaymentHandler(PaymentService paymentService, PaymentProviderRegistry providers) {
        this.paymentService = paymentService;
        this.providers = providers;
    }

    /**
     * Public: the checkout page needs this before the shopper is authenticated, and it
     * leaks nothing — names, labels and how each provider is confirmed. The frontend
     * renders a selector only when this returns more than one.
     */
    public Mono<ServerResponse> listProviders(ServerRequest request) {
        var body = providers.enabled().stream()
                .map(p -> Map.of(
                        "id", p.name(),
                        "displayName", p.displayName(),
                        "confirmationMode", p.confirmationMode().name(),
                        "webhookEnabled", p.webhookEnabled()))
                .toList();
        return ServerResponse.ok().bodyValue(body);
    }

    public Mono<ServerResponse> createPaymentIntent(ServerRequest request) {
        return request.bodyToMono(CreatePaymentIntentRequest.class)
                .flatMap(req -> paymentService.createPaymentIntent(
                        req.orderId(), req.total(), req.currency(), req.username(), req.provider()))
                .flatMap(payment -> ServerResponse.ok().bodyValue(
                        PaymentService.toResponse(payment, null)))
                // A provider the shopper named that we do not have, or none named while
                // several are enabled, is a bad request — not a 500, and not a silent
                // choice made on their behalf.
                .onErrorResume(PaymentProviderRegistry.UnknownProviderException.class,
                        e -> ServerResponse.badRequest().bodyValue(Map.of("error", e.getMessage())))
                .onErrorResume(PaymentProviderRegistry.AmbiguousProviderException.class,
                        e -> ServerResponse.badRequest().bodyValue(Map.of("error", e.getMessage())));
    }

    public Mono<ServerResponse> getPaymentByOrderId(ServerRequest request) {
        Long orderId = Long.valueOf(request.pathVariable("orderId"));
        return paymentService.getPaymentByOrderId(orderId)
                .flatMap(response -> ServerResponse.ok().bodyValue(response))
                .switchIfEmpty(ServerResponse.notFound().build());
    }

    // Internal, SCOPE_internal only. shop asks this before purging a user's
    // orders, because order status cannot tell you whether money moved
    // (docs/users/blocking-users.md §2.3).
    public Mono<ServerResponse> getStatusesByOrderIds(ServerRequest request) {
        return request.bodyToMono(OrderIdsRequest.class)
                .map(req -> req.orderIds() == null ? List.<Long>of() : req.orderIds())
                .flatMap(paymentService::statusesByOrderIds)
                .flatMap(statuses -> ServerResponse.ok().bodyValue(statuses));
    }

    public record OrderIdsRequest(List<Long> orderIds) {}

    // Orphan sweep (docs/users/blocking-users.md §8 Phase 6), read-only.
    public Mono<ServerResponse> getOrderIds(ServerRequest request) {
        return paymentService.distinctOrderIds()
                .flatMap(orderIds -> ServerResponse.ok().bodyValue(orderIds));
    }

    public Mono<ServerResponse> retryPaymentIntent(ServerRequest request) {
        Long orderId = Long.valueOf(request.pathVariable("orderId"));
        return paymentService.retryPaymentIntent(orderId)
                .flatMap(payment -> ServerResponse.ok().bodyValue(
                        PaymentService.toResponse(payment, null)))
                .onErrorResume(e -> {
                    log.error("Failed to retry payment for order {}: {}", orderId, e.getMessage());
                    return ServerResponse.badRequest().bodyValue(Map.of("error", e.getMessage()));
                });
    }

    public Mono<ServerResponse> syncPaymentStatus(ServerRequest request) {
        Long orderId = Long.valueOf(request.pathVariable("orderId"));
        return paymentService.syncPaymentStatus(orderId)
                .flatMap(response -> ServerResponse.ok().bodyValue(response))
                .onErrorResume(e -> {
                    log.error("Failed to sync payment status for order {}: {}", orderId, e.getMessage());
                    return ServerResponse.badRequest().bodyValue(Map.of("error", e.getMessage()));
                });
    }
}
