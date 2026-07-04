package org.granitesecurity.payment.handler;

import org.granitesecurity.payment.dto.CreatePaymentIntentRequest;
import org.granitesecurity.payment.dto.CreatePaymentIntentResponse;
import org.granitesecurity.payment.repository.PaymentRepository;
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
    private final PaymentRepository paymentRepository;

    public PaymentHandler(PaymentService paymentService, PaymentRepository paymentRepository) {
        this.paymentService = paymentService;
        this.paymentRepository = paymentRepository;
    }

    public Mono<ServerResponse> createPaymentIntent(ServerRequest request) {
        return request.bodyToMono(CreatePaymentIntentRequest.class)
                .flatMap(req -> paymentService.createPaymentIntent(
                        req.orderId(), req.total(), req.currency(), req.username()))
                .flatMap(payment -> ServerResponse.ok().bodyValue(
                        new CreatePaymentIntentResponse(
                                payment.getId(),
                                payment.getOrderId(),
                                payment.getStripePaymentIntentId(),
                                payment.getClientSecret(),
                                payment.getStatus(),
                                payment.getAmount(),
                                payment.getCurrency(),
                                payment.getCreatedAt()
                        )));
    }

    public Mono<ServerResponse> getPaymentByOrderId(ServerRequest request) {
        Long orderId = Long.valueOf(request.pathVariable("orderId"));
        return paymentRepository.findByOrderId(orderId)
                .flatMap(payment -> ServerResponse.ok().bodyValue(
                        new CreatePaymentIntentResponse(
                                payment.getId(),
                                payment.getOrderId(),
                                payment.getStripePaymentIntentId(),
                                payment.getClientSecret(),
                                payment.getStatus(),
                                payment.getAmount(),
                                payment.getCurrency(),
                                payment.getCreatedAt()
                        )))
                .switchIfEmpty(ServerResponse.notFound().build());
    }

    public Mono<ServerResponse> syncPaymentStatus(ServerRequest request) {
        Long orderId = Long.valueOf(request.pathVariable("orderId"));
        return paymentService.syncPaymentStatus(orderId)
                .flatMap(payment -> ServerResponse.ok().bodyValue(
                        new CreatePaymentIntentResponse(
                                payment.getId(),
                                payment.getOrderId(),
                                payment.getStripePaymentIntentId(),
                                payment.getClientSecret(),
                                payment.getStatus(),
                                payment.getAmount(),
                                payment.getCurrency(),
                                payment.getCreatedAt()
                        )))
                .onErrorResume(e -> {
                    log.error("Failed to sync payment status for order {}: {}", orderId, e.getMessage());
                    return ServerResponse.badRequest().bodyValue(Map.of("error", e.getMessage()));
                });
    }
}
