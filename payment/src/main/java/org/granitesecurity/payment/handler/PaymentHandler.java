package org.granitesecurity.payment.handler;

import org.granitesecurity.payment.dto.CreatePaymentIntentRequest;
import org.granitesecurity.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
public class PaymentHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentHandler.class);

    private final PaymentService paymentService;

    public PaymentHandler(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    public Mono<ServerResponse> createPaymentIntent(ServerRequest request) {
        return request.bodyToMono(CreatePaymentIntentRequest.class)
                .flatMap(req -> paymentService.createPaymentIntent(
                        req.orderId(), req.total(), req.currency(), req.username()))
                .flatMap(payment -> ServerResponse.ok().bodyValue(
                        PaymentService.toResponse(payment, null)));
    }

    public Mono<ServerResponse> getPaymentByOrderId(ServerRequest request) {
        Long orderId = Long.valueOf(request.pathVariable("orderId"));
        return paymentService.getPaymentByOrderId(orderId)
                .flatMap(response -> ServerResponse.ok().bodyValue(response))
                .switchIfEmpty(ServerResponse.notFound().build());
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
