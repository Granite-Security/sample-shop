package org.granitesecurity.delivery.consumer;

import org.granitesecurity.delivery.service.DeliveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DeliveryService deliveryService;

    public PaymentEventConsumer(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @KafkaListener(topics = "payments.events", groupId = "delivery.payment.consumer")
    public void consume(String message) {
        try {
            Map<String, Object> data = MAPPER.readValue(message, Map.class);
            String status = data.get("status") != null ? data.get("status").toString() : null;
            Long orderId = parseOrderId(data.get("orderId"));
            if (orderId == null) {
                log.warn("Payment event missing orderId: {}", message);
                return;
            }
            if (status == null) {
                log.debug("Ignoring payment event without status for order {}: {}", orderId, message);
                return;
            }

            String paymentStatus = switch (status) {
                case "SUCCEEDED" -> "PAID";
                case "FAILED" -> "FAILED";
                case "CANCELED" -> "CANCELED";
                default -> null;
            };
            if (paymentStatus == null) {
                log.warn("Unhandled payment status {} for order {}", status, orderId);
                return;
            }

            deliveryService.updatePaymentStatus(orderId, paymentStatus)
                    .subscribe(d -> log.info("Updated payment status to {} for order {}", paymentStatus, orderId),
                            err -> log.error("Failed to update payment status for order {}: {}", orderId, err.getMessage()));
        } catch (Exception e) {
            log.error("Failed to process payment event: {}", message, e);
        }
    }

    private static Long parseOrderId(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
