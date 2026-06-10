package org.granitesecurity.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.factory.TokenRelayGatewayFilterFactory;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
                        .filters(f->f.filter(tokenRelay.apply()))
                        .uri(greetingsServiceUri))
                .route("shop-service", r -> r
                        .path("/api/shop/**")
                        .filters(f -> f.filter(tokenRelay.apply()))
                        .uri(shopServiceUri))
                .build();
    }
}
