package org.granitesecurity.shop.handler;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.granitesecurity.shop.dto.OrderResponse;
import org.granitesecurity.shop.dto.PlaceOrderRequest;
import org.granitesecurity.shop.service.OrderService;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.granitesecurity.shop.web.RequestOrigin;
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
        var bodyMono = request.bodyToMono(PlaceOrderRequest.class);
        var usernameMono = getUsername(request);
        return bodyMono.zipWith(usernameMono)
                .flatMap(tuple -> orderService.placeOrder(
                        tuple.getT2(), tuple.getT1(), RequestOrigin.from(request)))
                .flatMap(order -> ServerResponse.ok().bodyValue(order));
    }

    @Operation(operationId = "getOrders", summary = "List current user's orders", description = "Returns orders for the authenticated user only")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paginated list of orders"),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content())
    })
    public Mono<ServerResponse> getOrders(ServerRequest request) {
        int page = Integer.parseInt(request.queryParam("page").orElse("0"));
        int size = Integer.parseInt(request.queryParam("size").orElse("20"));
        return getUsername(request)
                .flatMap(username -> orderService.getOrdersForUser(username, page, size))
                .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    @Operation(operationId = "getAllOrders", summary = "List all orders", description = "Admin only — returns orders from every user")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paginated list of all orders"),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN role", content = @Content()),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content())
    })
    public Mono<ServerResponse> getAllOrders(ServerRequest request) {
        int page = Integer.parseInt(request.queryParam("page").orElse("0"));
        int size = Integer.parseInt(request.queryParam("size").orElse("20"));
        return orderService.getAllOrders(page, size)
                .flatMap(result -> ServerResponse.ok().bodyValue(result));
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
        return request.principal()
                .cast(Authentication.class)
                .flatMap(auth -> orderService.getOrder(
                        id, ((Jwt) auth.getCredentials()).getSubject(), isAdmin(auth)))
                .flatMap(order -> ServerResponse.ok().bodyValue(order));
    }

    @Operation(operationId = "refundOrder", summary = "Request a refund for an order", description = "Users can refund an order whose delivery failed; admins can refund any paid order")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Refund requested — order marked RETURNED"),
            @ApiResponse(responseCode = "404", description = "Order not found or not owned by user", content = @Content()),
            @ApiResponse(responseCode = "409", description = "Order is not eligible for a refund", content = @Content()),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content())
    })
    public Mono<ServerResponse> refundOrder(ServerRequest request) {
        Long id = Long.valueOf(request.pathVariable("id"));
        return request.principal()
                .cast(Authentication.class)
                .flatMap(auth -> orderService.requestRefund(
                        id, ((Jwt) auth.getCredentials()).getSubject(), isAdmin(auth)))
                .flatMap(order -> ServerResponse.ok().bodyValue(order));
    }

    private static boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private Mono<String> getUsername(ServerRequest request) {
        return request.principal()
                .cast(Authentication.class)
                .map(auth -> ((Jwt) auth.getCredentials()).getSubject());
    }
}
