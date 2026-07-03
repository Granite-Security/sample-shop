package org.granitesecurity.payment.handler;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentListParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
public class HealthHandler {

    private static final Logger log = LoggerFactory.getLogger(HealthHandler.class);

    public Mono<ServerResponse> health(ServerRequest request) {
        return ServerResponse.ok().bodyValue(Map.of("status", "UP"));
    }

    public Mono<ServerResponse> stripeHealth(ServerRequest request) {
        try {
            var params = PaymentIntentListParams.builder().setLimit(1L).build();
            var intents = PaymentIntent.list(params);
            return ServerResponse.ok().bodyValue(Map.of(
                    "status", "UP",
                    "stripe", "connected",
                    "mode", intents.getData().isEmpty() ? "test" : "live"
            ));
        } catch (StripeException e) {
            log.warn("Stripe health check failed: {}", e.getMessage());
            return ServerResponse.ok().bodyValue(Map.of(
                    "status", "DEGRADED",
                    "stripe", "error",
                    "message", e.getMessage()
            ));
        }
    }
}
