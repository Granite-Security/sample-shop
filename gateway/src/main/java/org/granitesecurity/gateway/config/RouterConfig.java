package org.granitesecurity.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.net.URI;

@Configuration
public class RouterConfig {

    @Value("${microservices.greetings.uri}")
    private String greetingsServiceUri;

    @Value("${microservices.shop.uri}")
    private String shopServiceUri;

    @Value("${microservices.auth-server.uri:http://localhost:9090}")
    private String authServerUri;

    @Value("${app.spa-origin:http://localhost:5173}")
    private String spaOrigin;

    @Bean
    RouteLocator gatewayRouter(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("greetings-service", r -> r
                        .path("/api/greetings/**")
                        .uri(greetingsServiceUri))
                .route("greetings-secured", r -> r
                        .path("/api/secured/**")
                        .uri(greetingsServiceUri))
                .route("shop-service", r -> r
                        .path("/api/shop/**")
                        .uri(shopServiceUri))
                .route("shop-openapi", r -> r
                        .path("/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html", "/webjars/swagger-ui/**")
                        .uri(shopServiceUri))
                .route("auth-server", r -> r
                        .path("/auth/**")
                        .uri(authServerUri))
                .build();
    }

    @Bean
    RouterFunction<ServerResponse> indexRedirect() {
        return RouterFunctions.route()
                .GET("/", request ->
                        ServerResponse.status(HttpStatus.FOUND)
                                .headers(h -> h.setLocation(URI.create(spaOrigin + "/")))
                                .build())
                .build();
    }

}
