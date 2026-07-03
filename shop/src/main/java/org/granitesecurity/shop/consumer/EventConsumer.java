package org.granitesecurity.shop.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.granitesecurity.shop.domain.OrderStatus;
import org.granitesecurity.shop.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OrderService orderService;

    public EventConsumer(OrderService orderService) {
        this.orderService = orderService;
    }

    @KafkaListener(topics = "payments.events", groupId = "shop.payments.events.consumer")
    void onPaymentEvent(String message) {
        try {
            Map<String, Object> data = MAPPER.readValue(message, Map.class);
            Long orderId = parseOrderId(data.get("orderId"));
            if (orderId == null) {
                log.warn("Payment event missing orderId: {}", message);
                return;
            }
            if (data.containsKey("paymentId")) {
                orderService.updateOrderStatus(orderId, OrderStatus.PAID).block();
                log.info("PaymentReceived -> order {} marked PAID", orderId);
            } else if (data.containsKey("reason")) {
                orderService.updateOrderStatus(orderId, OrderStatus.PAYMENT_FAILED).block();
                log.info("PaymentFailed -> order {} marked PAYMENT_FAILED", orderId);
            } else {
                log.warn("Unknown payment event type: {}", message);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse payment event: {}", message, e);
        }
    }

    @KafkaListener(topics = "shipments.events", groupId = "shop.shipments.events.consumer")
    void onShipmentEvent(String message) {
        try {
            Map<String, Object> data = MAPPER.readValue(message, Map.class);
            Long orderId = parseOrderId(data.get("orderId"));
            if (orderId == null) {
                log.warn("Shipment event missing orderId: {}", message);
                return;
            }
            if (data.containsKey("carrier")) {
                orderService.updateOrderStatus(orderId, OrderStatus.SHIPPED).block();
                log.info("ShipmentDispatched -> order {} marked SHIPPED", orderId);
            } else if (data.containsKey("deliveredAt")) {
                orderService.updateOrderStatus(orderId, OrderStatus.DELIVERED).block();
                log.info("ShipmentDelivered -> order {} marked DELIVERED", orderId);
            } else {
                log.warn("Unknown shipment event type: {}", message);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse shipment event: {}", message, e);
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
