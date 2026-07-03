package org.granitesecurity.payment.route;

import org.granitesecurity.payment.handler.HealthHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class PaymentRoute {

    @Bean
    public RouterFunction<ServerResponse> paymentRoutes(HealthHandler healthHandler) {
        return RouterFunctions.route()
                .GET("/actuator/health", healthHandler::health)
                .GET("/actuator/health/stripe", healthHandler::stripeHealth)
                .build();
    }
}
