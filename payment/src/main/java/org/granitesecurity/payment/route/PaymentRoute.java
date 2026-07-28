package org.granitesecurity.payment.route;

import org.granitesecurity.payment.handler.HealthHandler;
import org.granitesecurity.payment.handler.PaymentHandler;
import org.granitesecurity.payment.handler.WebhookHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class PaymentRoute {

    @Bean
    public RouterFunction<ServerResponse> paymentRoutes(
            HealthHandler healthHandler,
            PaymentHandler paymentHandler,
            WebhookHandler webhookHandler) {
        return RouterFunctions.route()
                .GET("/actuator/health", healthHandler::health)
                .GET("/actuator/health/stripe", healthHandler::stripeHealth)
                .POST("/api/payments/intent", paymentHandler::createPaymentIntent)
                .GET("/api/payments/intent/{orderId}", paymentHandler::getPaymentByOrderId)
                .POST("/api/payments/intent/{orderId}/sync", paymentHandler::syncPaymentStatus)
                .POST("/api/payments/intent/{orderId}/retry", paymentHandler::retryPaymentIntent)
                .POST("/api/payments/internal/statuses", paymentHandler::getStatusesByOrderIds)
                .GET("/api/payments/internal/order-ids", paymentHandler::getOrderIds)
                .POST("/api/payments/webhook", webhookHandler::handleWebhook)
                .build();
    }
}
