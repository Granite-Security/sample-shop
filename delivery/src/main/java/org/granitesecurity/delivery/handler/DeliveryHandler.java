package org.granitesecurity.delivery.handler;

import org.granitesecurity.delivery.service.DeliveryService;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

@Component
public class DeliveryHandler {

    private final DeliveryService deliveryService;

    public DeliveryHandler(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    // Orphan sweep (docs/users/blocking-users.md §8 Phase 6), read-only.
    public Mono<ServerResponse> getOrderIds(ServerRequest request) {
        return deliveryService.distinctOrderIds()
                .flatMap(orderIds -> ServerResponse.ok().bodyValue(orderIds));
    }

    /**
     * {@code GET /api/delivery?status=&paymentStatus=&from=&to=&sort=&dir=&page=&size=}
     *
     * <p>Returns a {@code PagedResult}, not a bare array — a breaking change made
     * deliberately, matching what {@code shop} already does for orders and the catalog.
     *
     * <p>{@code from}/{@code to} are ISO-8601 instants and the window is half-open,
     * {@code [from, to)}. The callers hold a date picker, so they resolve a day to an
     * instant themselves: doing it here would have to guess a timezone, and the
     * shopper's is the only one that makes the boundary mean what they expect.
     *
     * <p>Unparseable numbers and dates fall back to their defaults instead of raising.
     * A malformed query string is a 200 with the default page, not a 500.
     */
    public Mono<ServerResponse> list(ServerRequest request) {
        Optional<String> status = request.queryParam("status");
        Optional<String> paymentStatus = request.queryParam("paymentStatus");
        Instant from = instantParam(request, "from");
        Instant to = instantParam(request, "to");
        String sort = request.queryParam("sort").orElse("orderId");
        boolean ascending = "asc".equalsIgnoreCase(request.queryParam("dir").orElse("desc"));
        int page = intParam(request, "page", 0);
        int size = intParam(request, "size", 20);

        return deliveryService.getDeliveries(status.orElse(null), paymentStatus.orElse(null),
                        from, to, sort, ascending, page, size)
                .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    private static int intParam(ServerRequest request, String name, int fallback) {
        try {
            return Integer.parseInt(request.queryParam(name).orElse(String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Instant instantParam(ServerRequest request, String name) {
        Optional<String> raw = request.queryParam(name).filter(v -> !v.isBlank());
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(raw.get());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public Mono<ServerResponse> getByOrderId(ServerRequest request) {
        Long orderId = Long.parseLong(request.pathVariable("orderId"));
        return deliveryService.getDeliveryByOrderId(orderId)
                .flatMap(response -> ServerResponse.ok().bodyValue(response))
                .switchIfEmpty(ServerResponse.notFound().build());
    }

    public Mono<ServerResponse> getTracking(ServerRequest request) {
        Long orderId = Long.parseLong(request.pathVariable("orderId"));
        return deliveryService.getTrackingDetail(orderId)
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
