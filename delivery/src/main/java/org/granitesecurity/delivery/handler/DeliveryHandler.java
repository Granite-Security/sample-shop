package org.granitesecurity.delivery.handler;

import org.granitesecurity.delivery.dto.DeliveryResponse;
import org.granitesecurity.delivery.service.DeliveryService;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

@Component
public class DeliveryHandler {

    private final DeliveryService deliveryService;

    public DeliveryHandler(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    public Mono<ServerResponse> list(ServerRequest request) {
        Optional<String> status = request.queryParam("status");
        Optional<String> paymentStatus = request.queryParam("paymentStatus");
        return ServerResponse.ok().body(
                deliveryService.getDeliveries(status.orElse(null), paymentStatus.orElse(null)),
                DeliveryResponse.class);
    }

    public Mono<ServerResponse> getByOrderId(ServerRequest request) {
        Long orderId = Long.parseLong(request.pathVariable("orderId"));
        return deliveryService.getDeliveryByOrderId(orderId)
                .flatMap(response -> ServerResponse.ok().bodyValue(response))
                .switchIfEmpty(ServerResponse.notFound().build());
    }

    public Mono<ServerResponse> updateStatus(ServerRequest request) {
        Long orderId = Long.parseLong(request.pathVariable("orderId"));
        return request.bodyToMono(Map.class)
                .flatMap(body -> {
                    String status = (String) body.get("status");
                    String description = (String) body.get("description");
                    if (status == null || status.isBlank()) {
                        return ServerResponse.badRequest().bodyValue(Map.of("error", "status is required"));
                    }
                    return deliveryService.updateStatus(orderId, status, description)
                            .flatMap(response -> ServerResponse.ok().bodyValue(response))
                            .onErrorResume(IllegalArgumentException.class,
                                    e -> ServerResponse.badRequest().bodyValue(Map.of("error", e.getMessage())));
                });
    }
}
