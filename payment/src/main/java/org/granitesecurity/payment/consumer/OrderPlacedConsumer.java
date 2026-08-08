package org.granitesecurity.payment.consumer;

import tools.jackson.databind.ObjectMapper;
import org.granitesecurity.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class OrderPlacedConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderPlacedConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PaymentService paymentService;



    private static final String ORDERS_EVENTS_TOPIC = "orders.events";
    private static final String ORDERS_EVENTS_GROUP_ID = "payment.orders.events.consumer";

    private static final String EVENT_TYPE_FIELD = "eventType";
    private static final String ORDER_PLACED_EVENT = "OrderPlaced";
    private static final String ORDERS_PURGED_EVENT = "OrdersPurged";
    private static final String REFUND_REQUESTED_EVENT = "RefundRequested";

    private static final String ORDER_ID_FIELD = "orderId";
    private static final String ORDER_IDS_FIELD = "orderIds";
    private static final String TOTAL_FIELD = "total";
    private static final String USERNAME_FIELD = "username";
    private static final String CURRENCY_FIELD = "currency";
    private static final String PROVIDER_FIELD = "provider";

    public OrderPlacedConsumer(PaymentService paymentService) {
        this.paymentService = paymentService;
    }


    @KafkaListener(topics = ORDERS_EVENTS_TOPIC, groupId = ORDERS_EVENTS_GROUP_ID)
    void onOrdersEvent(String message) {
        try {
            Map<String, Object> data = MAPPER.readValue(message, Map.class);

            String eventType = stringValue(data.get(EVENT_TYPE_FIELD));
            if (eventType == null) {
                log.warn("Untagged message on {}, assuming {}: {}", ORDERS_EVENTS_TOPIC, ORDER_PLACED_EVENT, message);
                eventType = ORDER_PLACED_EVENT;
            }


            if (isUnsupportedEventType(eventType)) {
                log.warn("Ignoring unknown event type '{}' on {}: {}", eventType, ORDERS_EVENTS_TOPIC, message);
                return;
            }

            switch (eventType) {
                case ORDERS_PURGED_EVENT -> handleOrdersPurged(data, message);
                case REFUND_REQUESTED_EVENT -> handleRefundRequested(data, message);
                case ORDER_PLACED_EVENT -> handleOrderPlaced(data, message);
                default -> throw new IllegalStateException("Unhandled orders event type: " + eventType);
            }
        } catch (Exception e) {
            log.error("Failed to handle {} message: {}", ORDERS_EVENTS_TOPIC, message, e);
        }
    }

    private void handleOrdersPurged(Map<String, Object> data, String message) {
        // Carries "orderIds" (plural) and no "orderId".
        List<Long> orderIds = parseLongList(data.get(ORDER_IDS_FIELD));
        if (orderIds.isEmpty()) {
            log.warn("{} event with no {}: {}", ORDERS_PURGED_EVENT, ORDER_IDS_FIELD, message);
            return;
        }

        log.info("Processing {} event for {} order(s)", ORDERS_PURGED_EVENT, orderIds.size());
        paymentService.purgeOrders(orderIds).block();
    }

    private void handleRefundRequested(Map<String, Object> data, String message) {
        Long orderId = parseLong(data.get(ORDER_ID_FIELD));
        if (orderId == null) {
            log.warn("{} event missing {}: {}", REFUND_REQUESTED_EVENT, ORDER_ID_FIELD, message);
            return;
        }

        log.info("Processing {} event for order {}", REFUND_REQUESTED_EVENT, orderId);
        paymentService.processRefundRequested(orderId).block();
    }

    private void handleOrderPlaced(Map<String, Object> data, String message) {
        Long orderId = parseLong(data.get(ORDER_ID_FIELD));
        if (orderId == null) {
            log.warn("{} event missing {}: {}", ORDER_PLACED_EVENT, ORDER_ID_FIELD, message);
            return;
        }

        BigDecimal total = parseBigDecimal(data.get(TOTAL_FIELD));
        String username = stringValue(data.get(USERNAME_FIELD));

        // Both optional only for events published before shop carried them: current
        // orders always name a provider, and shop rejects one that does not. Null
        // currency falls back to the configured shop currency; null provider to the
        // only enabled one, which fails loudly when several are.
        String currency = stringValue(data.get(CURRENCY_FIELD));
        String provider = stringValue(data.get(PROVIDER_FIELD));

        paymentService.processOrderPlaced(orderId, total, username, currency, provider).block();
    }

    private static boolean isUnsupportedEventType(String eventType) {
        return !ORDER_PLACED_EVENT.equals(eventType)
                && !ORDERS_PURGED_EVENT.equals(eventType)
                && !REFUND_REQUESTED_EVENT.equals(eventType);
    }

    private static String stringValue(Object value) {
        return value != null ? value.toString() : null;
    }

// ... existing code ...

    private static List<Long> parseLongList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(OrderPlacedConsumer::parseLong).filter(Objects::nonNull).toList();
    }

    private static Long parseLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static BigDecimal parseBigDecimal(Object value) {
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        if (value instanceof String s) {
            try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}
