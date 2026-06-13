package org.granitesecurity.shop.handler;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.granitesecurity.shop.dto.OrderResponse;
import org.granitesecurity.shop.dto.PlaceOrderRequest;
import org.granitesecurity.shop.service.OrderService;
import org.granitesecurity.shop.service.ShopException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Service
public class OrderHandler {

    private final OrderService orderService;

    public OrderHandler(OrderService orderService) {
        this.orderService = orderService;
    }

    @Operation(operationId = "placeOrder", summary = "Place an order", description = "Authenticated users place orders. Stock is decremented.")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order placed successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error — insufficient stock or invalid items", content = @Content()),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content())
    })
    public Mono<ServerResponse> placeOrder(ServerRequest request) {
        return request.bodyToMono(PlaceOrderRequest.class)
                .zipWith(getUsername(request))
                .flatMap(tuple -> orderService.placeOrder(tuple.getT2(), tuple.getT1()))
                .flatMap(order -> ServerResponse.ok().bodyValue(order))
                .onErrorResume(ShopException.class,
                        e -> ServerResponse.badRequest().bodyValue(e.getMessage()));
    }

    @Operation(operationId = "getOrders", summary = "List current user's orders", description = "Returns orders for the authenticated user only")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of orders"),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content())
    })
    public Mono<ServerResponse> getOrders(ServerRequest request) {
        return getUsername(request)
                .flatMapMany(orderService::getOrdersForUser)
                .collectList()
                .flatMap(orders -> ServerResponse.ok().bodyValue(orders));
    }

    @Operation(operationId = "getOrder", summary = "Get an order by ID", description = "Returns the order only if it belongs to the authenticated user")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order found"),
            @ApiResponse(responseCode = "404", description = "Order not found or not owned by user", content = @Content()),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content())
    })
    public Mono<ServerResponse> getOrder(ServerRequest request) {
        Long id = Long.valueOf(request.pathVariable("id"));
        return getUsername(request)
                .flatMap(username -> orderService.getOrder(id, username))
                .flatMap(order -> ServerResponse.ok().bodyValue(order))
                .onErrorResume(ShopException.class, e -> ServerResponse.notFound().build());
    }

    private Mono<String> getUsername(ServerRequest request) {
        return request.principal()
                .cast(Authentication.class)
                .map(auth -> ((Jwt) auth.getCredentials()).getSubject());
    }
}
