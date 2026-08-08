package org.granitesecurity.profile.consumer;

import org.granitesecurity.profile.service.OrderNoticeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Tells admin, in their profile inbox, that someone placed an order.
 *
 * <p>Second topic profile consumes, alongside identity.events. It is a topic of its own
 * rather than orders.events because this consumer wants one fact and none of the order:
 * subscribing to orders.events would mean profile dispatching on event types it has no
 * part in, and reading items, addresses and totals it should not hold.
 */
@Component
public class OrderNoticeConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderNoticeConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ORDER_PLACED_NOTICE = "OrderPlacedNotice";

    private final OrderNoticeService orderNoticeService;
    private final Duration maxAge;

    public OrderNoticeConsumer(OrderNoticeService orderNoticeService,
                               @Value("${profile.order-notices.max-age:PT24H}") Duration maxAge) {
        this.orderNoticeService = orderNoticeService;
        this.maxAge = maxAge;
    }

    @KafkaListener(topics = "shop.notifications", groupId = "profile.shop.notifications.consumer")
    public void consume(String message) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = MAPPER.readValue(message, Map.class);
            if (!ORDER_PLACED_NOTICE.equals(string(data.get("eventType")))) {
                log.debug("Ignoring event type '{}' on shop.notifications", string(data.get("eventType")));
                return;
            }

            String username = string(data.get("username"));
            Long orderId = parseLong(data.get("orderId"));
            if (username == null || username.isBlank() || orderId == null) {
                log.warn("{} missing username or orderId: {}", ORDER_PLACED_NOTICE, message);
                return;
            }

            // profile reads with auto-offset-reset: earliest, so a group that loses its
            // offsets replays the whole retention window. Announcing yesterday's orders
            // as news is worse than not announcing them, so old ones are dropped —
            // the dedupe table cannot help here, these are distinct orders.
            if (isStale(data.get("occurredAt"))) {
                log.warn("Dropping stale {} for order {} — older than {}", ORDER_PLACED_NOTICE, orderId, maxAge);
                return;
            }

            orderNoticeService.notifyAdmin(username, orderId).block();
        } catch (Exception e) {
            log.error("Failed to handle shop.notifications message: {}", message, e);
        }
    }

    /**
     * An unparseable or absent timestamp counts as fresh: dropping a real order notice
     * because a field was malformed loses the thing we are trying to deliver, and the
     * dedupe table already bounds the damage of processing one twice.
     */
    private boolean isStale(Object occurredAt) {
        String value = string(occurredAt);
        if (value == null) {
            return false;
        }
        try {
            return Instant.parse(value).isBefore(Instant.now().minus(maxAge));
        } catch (Exception e) {
            log.warn("Unparseable occurredAt '{}', treating as fresh", value);
            return false;
        }
    }

    private static String string(Object value) {
        return value != null ? value.toString() : null;
    }

    private static Long parseLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}
