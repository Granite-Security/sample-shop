package org.granitesecurity.shop.handler;

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

    public Mono<ServerResponse> placeOrder(ServerRequest request) {
        return request.bodyToMono(PlaceOrderRequest.class)
                .zipWith(getUsername(request))
                .flatMap(tuple -> orderService.placeOrder(tuple.getT2(), tuple.getT1()))
                .flatMap(order -> ServerResponse.ok().bodyValue(order))
                .onErrorResume(ShopException.class,
                        e -> ServerResponse.badRequest().bodyValue(e.getMessage()));
    }

    public Mono<ServerResponse> getOrders(ServerRequest request) {
        return getUsername(request)
                .flatMapMany(orderService::getOrdersForUser)
                .collectList()
                .flatMap(orders -> ServerResponse.ok().bodyValue(orders));
    }

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
