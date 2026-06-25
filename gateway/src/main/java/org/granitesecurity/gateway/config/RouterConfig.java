package org.granitesecurity.gateway.config;

import org.granitesecurity.gateway.handler.UserHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.factory.TokenRelayGatewayFilterFactory;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class RouterConfig {

    @Value("${microservices.greetings.uri}")
    private String greetingsServiceUri;

    @Value("${microservices.shop.uri}")
    private String shopServiceUri;

    @Bean
    RouteLocator gatewayRouter(RouteLocatorBuilder builder, TokenRelayGatewayFilterFactory tokenRelay) {
        return builder.routes()
                .route("greetings-service", r -> r
                        .path("/api/greetings/**")
                        .uri(greetingsServiceUri))
                .route("greetings-secured", r -> r
                        .path("/api/secured/**")
                        .filters(f -> f.filter(tokenRelay.apply()))
                        .uri(greetingsServiceUri))
                .route("shop-public", r -> r
                        .path("/api/shop/products/**",
                                "/api/shop/categories", "/api/shop/categories/**")
                        .uri(shopServiceUri))
                .route("shop-service", r -> r
                        .path("/api/shop/**")
                        .filters(f -> f.filter(tokenRelay.apply()))
                        .uri(shopServiceUri))
                .route("shop-openapi", r -> r
                        .path("/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html", "/webjars/swagger-ui/**")
                        .uri(shopServiceUri))
                .build();
    }

    @Bean
    RouterFunction<ServerResponse> frontendRoutes(UserHandler handler) {
        return RouterFunctions.route()
                .GET("/api/user/me", handler::me)
                .GET("/assets/**", request -> ServerResponse.ok()
                        .body(BodyInserters.fromResource(
                                new ClassPathResource("static" + request.path()))))
                .GET("/favicon.svg", request -> ServerResponse.ok()
                        .body(BodyInserters.fromResource(
                                new ClassPathResource("static/favicon.svg"))))
                .GET("/icons.svg", request -> ServerResponse.ok()
                        .body(BodyInserters.fromResource(
                                new ClassPathResource("static/icons.svg"))))
                .build();
    }

}
