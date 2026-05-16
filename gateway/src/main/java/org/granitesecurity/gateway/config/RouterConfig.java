package org.granitesecurity.gateway.config;

import org.springframework.cloud.gateway.filter.factory.TokenRelayGatewayFilterFactory;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RouterConfig {
    @Bean
    RouteLocator gatewayRouter(RouteLocatorBuilder builder, TokenRelayGatewayFilterFactory tokenRelay) {
        return builder.routes()
                .route("greetings-service", r -> r
                        .path("/api/greetings/**")
                        .uri("http://localhost:8060"))
                .route("greetings-secured", r -> r
                        .path("/api/secured/**")
                        .filters(f->f.filter(tokenRelay.apply()))
                        .uri("http://localhost:8060"))
                .build();
    }
}
