package org.granitesecurity.delivery.consumer;

import org.granitesecurity.delivery.service.DeliveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DeliveryService deliveryService;

    public OrderEventConsumer(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @KafkaListener(topics = "orders.events", groupId = "delivery.order.consumer")
    public void consume(String message) {
        try {
            Map<String, Object> data = MAPPER.readValue(message, Map.class);

            // Absent on messages published before shop tagged OrderPlaced. Drop the
            // null once no untagged message can still be in flight or sitting PENDING
            // in shop's outbox — until then, absent means OrderPlaced.
            Object eventType = data.get("eventType");
            if (eventType != null && !"OrderPlaced".equals(eventType) && !"OrdersPurged".equals(eventType)) {
                // Includes RefundRequested, which delivery has no part in. Logged at
                // debug rather than warn: this consumer is meant not to know every type
                // on a shared topic, and falling through would have it try to build a
                // delivery out of one.
                log.debug("Ignoring event type '{}' on orders.events", eventType);
                return;
            }

            // Carries "orderIds" (plural) and no "orderId"/"address", so it has
            // to be handled before the checks below reject it as malformed.
            if ("OrdersPurged".equals(eventType)) {
                List<Long> orderIds = parseOrderIds(data.get("orderIds"));
                if (orderIds.isEmpty()) {
                    log.warn("OrdersPurged event with no orderIds: {}", message);
                    return;
                }
                deliveryService.purgeOrders(orderIds)
                        .subscribe(ignored -> { },
                                err -> log.error("Failed to purge deliveries for {} order(s): {}",
                                        orderIds.size(), err.getMessage()),
                                () -> log.info("Purged deliveries for {} order(s)", orderIds.size()));
                return;
            }

            Long orderId = parseOrderId(data.get("orderId"));
            if (orderId == null) {
                log.warn("Order event missing orderId: {}", message);
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> address = (Map<String, Object>) data.get("address");
            if (address == null) {
                log.warn("Order event missing address: {}", message);
                return;
            }

            String recipientName = (String) address.get("recipientName");
            String addressLine1 = (String) address.get("addressLine1");
            String addressLine2 = (String) address.get("addressLine2");
            String city = (String) address.get("city");
            String state = (String) address.get("state");
            String zipCode = (String) address.get("zipCode");
            String country = (String) address.get("country");

            String items = data.get("items") != null ? MAPPER.writeValueAsString(data.get("items")) : null;

            deliveryService.createDelivery(orderId, recipientName, addressLine1,
                            addressLine2, city, state, zipCode, country, items)
                    .subscribe(d -> log.info("Created delivery request for order {}", orderId),
                            err -> log.error("Failed to create delivery for order {}: {}", orderId, err.getMessage()));
        } catch (Exception e) {
            log.error("Failed to process order event: {}", message, e);
        }
    }

    private static List<Long> parseOrderIds(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(OrderEventConsumer::parseOrderId).filter(Objects::nonNull).toList();
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
