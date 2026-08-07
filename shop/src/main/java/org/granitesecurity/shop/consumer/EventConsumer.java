package org.granitesecurity.shop.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.granitesecurity.shop.domain.OrderStatus;
import org.granitesecurity.shop.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * What each inbound event means for an order.
 *
 * <p>Every method returns a {@code Mono} that completes when the transition is durable,
 * and fails when it is not. Nothing here catches a failure to apply one: the failure is
 * the signal {@link EventPipeline} needs to decide between retrying and dead-lettering.
 * The previous version blocked on each update inside a try/catch, which committed the
 * offset whatever happened — a slow database silently lost a PAID transition while the
 * money had already moved.
 *
 * <p>A message that cannot be parsed raises {@link MalformedEventException}, the one
 * failure that must not be retried.
 */
@Component
public class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OrderService orderService;

    public EventConsumer(OrderService orderService) {
        this.orderService = orderService;
    }

    public Mono<Void> onPaymentEvent(String message) {
        return Mono.defer(() -> {
            Map<String, Object> data = parse(message, "payment");

            // A top-up funds a balance and has no order. It shares this topic with
            // order payments, so skip it here rather than warning about a missing
            // orderId on every one (docs/finance/finance.md §6.1).
            if ("TOPUP".equals(String.valueOf(data.get("purpose")))) {
                return Mono.empty();
            }

            Long orderId = parseOrderId(data.get("orderId"));
            if (orderId == null) {
                log.warn("Payment event missing orderId: {}", message);
                return Mono.empty();
            }
            Object status = data.get("status");
            if (status != null) {
                String s = status.toString();
                return switch (s) {
                    case "SUCCEEDED" -> transition(orderId, OrderStatus.PAID,
                            "PaymentSucceeded -> order {} marked PAID");
                    case "FAILED" -> transition(orderId, OrderStatus.PAYMENT_FAILED,
                            "PaymentFailed -> order {} marked PAYMENT_FAILED");
                    case "CANCELED" -> {
                        log.info("PaymentCanceled -> order {}, no status transition", orderId);
                        yield Mono.empty();
                    }
                    case "REFUNDED" -> transition(orderId, OrderStatus.REIMBURSED,
                            "PaymentRefunded -> order {} marked REIMBURSED");
                    // The provider walked a refund back after accepting it, so the money
                    // never reached the shopper. Return the order to RETURNED — the
                    // refund was still requested, it just did not complete — from where
                    // a retry can finish it. Logged at ERROR because someone has an
                    // order they believe was refunded and was not.
                    case "REFUND_FAILED" -> orderService.updateOrderStatus(orderId, OrderStatus.RETURNED)
                            .doOnSuccess(v -> log.error(
                                    "PaymentRefundFailed -> order {} returned to RETURNED; the refund did not complete",
                                    orderId));
                    default -> {
                        log.warn("Unknown payment status: {}", s);
                        yield Mono.empty();
                    }
                };
            }
            if (data.containsKey("paymentId")) {
                return transition(orderId, OrderStatus.PAID,
                        "PaymentReceived (legacy) -> order {} marked PAID");
            }
            if (data.containsKey("reason")) {
                return transition(orderId, OrderStatus.PAYMENT_FAILED,
                        "PaymentFailed (legacy) -> order {} marked PAYMENT_FAILED");
            }
            // Both keys accepted deliberately. payment publishes providerPaymentId
            // and the legacy stripePaymentIntentId side by side; dropping the legacy
            // branch would make messages already on the topic fall through to the
            // "unknown event type" warning during a rollout.
            if (data.containsKey("providerPaymentId") || data.containsKey("stripePaymentIntentId")) {
                log.info("PaymentIntentCreated for order {} ({}) — awaiting completion",
                        orderId, data.getOrDefault("provider", "unknown provider"));
                return Mono.empty();
            }
            log.warn("Unknown payment event type: {}", message);
            return Mono.empty();
        });
    }

    public Mono<Void> onShipmentEvent(String message) {
        return Mono.defer(() -> {
            Map<String, Object> data = parse(message, "shipment");
            Long orderId = parseOrderId(data.get("orderId"));
            if (orderId == null) {
                log.warn("Shipment event missing orderId: {}", message);
                return Mono.empty();
            }
            if (data.containsKey("carrier")) {
                return transition(orderId, OrderStatus.SHIPPED,
                        "ShipmentDispatched -> order {} marked SHIPPED");
            }
            if (data.containsKey("deliveredAt")) {
                return transition(orderId, OrderStatus.DELIVERED,
                        "ShipmentDelivered -> order {} marked DELIVERED");
            }
            log.warn("Unknown shipment event type: {}", message);
            return Mono.empty();
        });
    }

    public Mono<Void> onDeliveryEvent(String message) {
        return Mono.defer(() -> {
            Map<String, Object> data = parse(message, "delivery");
            Long orderId = parseOrderId(data.get("orderId"));
            if (orderId == null) {
                log.warn("Delivery event missing orderId: {}", message);
                return Mono.empty();
            }
            String status = data.get("status") != null ? data.get("status").toString() : null;
            if ("DISPATCHED".equals(status)) {
                // Both writes are part of the same transition: ordered, and either both
                // land or the record is retried from the start.
                return orderService.updateOrderStatus(orderId, OrderStatus.SHIPPED)
                        .then(orderService.updateDeliveryStatus(orderId, "DISPATCHED"))
                        .doOnSuccess(v -> log.info("DeliveryDispatched -> order {} marked SHIPPED", orderId));
            }
            if ("DELIVERED".equals(status)) {
                return orderService.updateOrderStatus(orderId, OrderStatus.DELIVERED)
                        .then(orderService.updateDeliveryStatus(orderId, "DELIVERED"))
                        .doOnSuccess(v -> log.info("DeliveryDelivered -> order {} marked DELIVERED", orderId));
            }
            if ("FAILED".equals(status)) {
                return orderService.updateDeliveryStatus(orderId, "FAILED")
                        .doOnSuccess(v -> log.info("DeliveryFailed -> order {} delivery status FAILED", orderId));
            }
            log.debug("Delivery status {} for order {}, no transition", status, orderId);
            return Mono.empty();
        });
    }

    private Mono<Void> transition(Long orderId, OrderStatus target, String successMessage) {
        return orderService.updateOrderStatus(orderId, target)
                .doOnSuccess(v -> log.info(successMessage, orderId));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String message, String kind) {
        try {
            return MAPPER.readValue(message, Map.class);
        } catch (JsonProcessingException e) {
            throw new MalformedEventException("Failed to parse " + kind + " event: " + message, e);
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
