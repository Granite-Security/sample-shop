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

    public OrderPlacedConsumer(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @KafkaListener(topics = "orders.events", groupId = "payment.orders.events.consumer")
    void onOrderPlaced(String message) {
        try {
            Map<String, Object> data = MAPPER.readValue(message, Map.class);
            // Carries "orderIds" (plural) and no "orderId" — so it must be
            // handled before the orderId lookup below, which would otherwise
            // reject it as a malformed OrderPlaced.
            if ("OrdersPurged".equals(data.get("eventType"))) {
                List<Long> orderIds = parseLongList(data.get("orderIds"));
                if (orderIds.isEmpty()) {
                    log.warn("OrdersPurged event with no orderIds: {}", message);
                    return;
                }
                log.info("Processing OrdersPurged event for {} order(s)", orderIds.size());
                paymentService.purgeOrders(orderIds).block();
                return;
            }
            if ("RefundRequested".equals(data.get("eventType"))) {
                Long refundOrderId = parseLong(data.get("orderId"));
                if (refundOrderId == null) {
                    log.warn("RefundRequested event missing orderId: {}", message);
                    return;
                }
                log.info("Processing RefundRequested event for order {}", refundOrderId);
                paymentService.processRefundRequested(refundOrderId).block();
                return;
            }
            Long orderId = parseLong(data.get("orderId"));
            if (orderId == null) {
                log.warn("OrderPlaced event missing orderId: {}", message);
                return;
            }
            BigDecimal total = parseBigDecimal(data.get("total"));
            String username = data.get("username") != null ? data.get("username").toString() : null;
            paymentService.processOrderPlaced(orderId, total, username).block();
        } catch (Exception e) {
            log.error("Failed to parse OrderPlaced event: {}", message, e);
        }
    }

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
