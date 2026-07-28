package org.granitesecurity.shop.handler;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.granitesecurity.shop.service.OrderService;
import org.granitesecurity.shop.service.UserOrderService;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Orders addressed by <em>username</em>. Deliberately not rooted under
 * /api/shop/orders/ — {id} there is an order id, so a {username} segment would
 * shadow it, the same trap ShopRoute already documents for /orders/all
 * (docs/users/blocking-users.md §5.3).
 */
@Service
public class UserOrderHandler {

    private final OrderService orderService;
    private final UserOrderService userOrderService;

    public UserOrderHandler(OrderService orderService, UserOrderService userOrderService) {
        this.orderService = orderService;
        this.userOrderService = userOrderService;
    }

    @Operation(operationId = "getOrdersByUsername", summary = "List one user's orders",
            description = "Admin only — the admin UI uses this to show what a delete would remove")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paginated list of that user's orders"),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN role", content = @Content())
    })
    public Mono<ServerResponse> getOrdersByUsername(ServerRequest request) {
        String username = request.pathVariable("username");
        int page = Integer.parseInt(request.queryParam("page").orElse("0"));
        int size = Integer.parseInt(request.queryParam("size").orElse("20"));
        return orderService.getOrdersForUser(username, page, size)
                .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    public Mono<ServerResponse> getPurgeEligibility(ServerRequest request) {
        return userOrderService.purgeEligibility(request.pathVariable("username"))
                .flatMap(eligibility -> ServerResponse.ok().bodyValue(eligibility));
    }

    public Mono<ServerResponse> purgeOrders(ServerRequest request) {
        return userOrderService.purgeOrders(request.pathVariable("username"))
                .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    // ── Orphan sweep (§8 Phase 6), read-only ────────────────────────

    public Mono<ServerResponse> listOrderOwners(ServerRequest request) {
        return ServerResponse.ok().bodyValue(userOrderService.orderOwners());
    }

    /** Which of the given order ids no longer exist — payment/delivery orphans. */
    public Mono<ServerResponse> findUnknownOrderIds(ServerRequest request) {
        return request.bodyToMono(OrderIdsRequest.class)
                .map(req -> req.orderIds() == null ? List.<Long>of() : req.orderIds())
                .flatMap(userOrderService::unknownOrderIds)
                .flatMap(unknown -> ServerResponse.ok().bodyValue(unknown));
    }

    public record OrderIdsRequest(List<Long> orderIds) {}
}
