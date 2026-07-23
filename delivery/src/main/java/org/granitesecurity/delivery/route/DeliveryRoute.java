package org.granitesecurity.delivery.route;

import org.granitesecurity.delivery.handler.DeliveryHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@Configuration
public class DeliveryRoute {

    @Bean
    public RouterFunction<ServerResponse> deliveryRoutes(DeliveryHandler deliveryHandler) {
        return route()
                .GET("/api/delivery", deliveryHandler::list)
                .GET("/api/delivery/{orderId}", deliveryHandler::getByOrderId)
                .GET("/api/delivery/{orderId}/tracking", deliveryHandler::getTracking)
                .PUT("/api/delivery/{orderId}/status", deliveryHandler::updateStatus)
                .build();
    }
}
