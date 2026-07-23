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

    @Value("${microservices.payment.uri:http://localhost:8062}")
    private String paymentServiceUri;

    @Value("${microservices.auth-server.uri:http://localhost:9090}")
    private String authServerUri;

    @Value("${microservices.profile.uri:http://localhost:8064}")
    private String profileServiceUri;

    @Value("${microservices.delivery.uri:http://localhost:8063}")
    private String deliveryServiceUri;

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
                .route("payment-service", r -> r
                        .path("/api/payments/**")
                        .uri(paymentServiceUri))
                .route("profile-service", r -> r
                        .path("/api/profiles/**")
                        .uri(profileServiceUri))
                .route("delivery-service", r -> r
                        .path("/api/delivery/**")
                        .uri(deliveryServiceUri))
                .route("auth-server", r -> r
                        .path("/auth/**")
                        .uri(authServerUri))
                .build();
    }

    @Bean
    RouterFunction<ServerResponse> indexRedirect() {
        // Previously 302'd to a single-valued spaOrigin — not viable once two
        // domains/frontends exist behind this one gateway, and a redirect back
        // to "/" would just loop. nginx never proxies "/" itself to gateway
        // (only /api, /auth, /oauth2), so this route only fires if something
        // bypasses nginx and hits gateway directly; a plain 200 is enough to
        // confirm the gateway is up without guessing which frontend to send
        // the caller to.
        return RouterFunctions.route()
                .GET("/", request -> ServerResponse.ok().bodyValue("gateway is up"))
                .build();
    }

}
