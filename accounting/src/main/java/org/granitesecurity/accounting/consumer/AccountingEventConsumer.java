package org.granitesecurity.accounting.consumer;

import org.granitesecurity.accounting.service.FactIngestService;
import org.granitesecurity.accounting.service.FactIngestService.IncomingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * The four topics the books are built from (docs/finance/accounting.md §4.2).
 *
 * <p>Every one of them is outbox-backed (D27). {@code identity.events} is deliberately
 * absent and must stay absent: it is fire-and-forget with accepted loss, which is right
 * for a courtesy email and fatal for a ledger.
 *
 * <p>These listeners do one job — translate a producer's shape into a normalised
 * {@link IncomingEvent} — and hand it to {@link FactIngestService}. What an event
 * <em>means</em> is not decided here; that is {@code PostingRules}, in one file (D24).
 *
 * <p>Blocking on the reactive chain is deliberate and confined to this class. The Kafka
 * listener container gives us a thread and expects the record to be finished when the
 * method returns; committing the offset before the transaction commits is how a rebalance
 * loses a fact. This is not a request-handling path, so it breaks no reactive guarantee.
 */
@Component
public class AccountingEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AccountingEventConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FactIngestService ingestService;

    public AccountingEventConsumer(FactIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @KafkaListener(topics = "orders.events", groupId = "accounting.orders.consumer")
    public void onOrderEvent(String message) {
        Map<String, Object> data = parse(message, "order");
        // shop tags every event now, but untagged messages predate the tagging and are
        // OrderPlaced — the event that existed first (see shop/README.md § Events).
        String eventType = string(data.getOrDefault("eventType", "OrderPlaced"));
        String orderId = string(data.get("orderId"));
        ingest(new IncomingEvent(
                "orders.events", eventType,
                key("orders.events", eventType, orderId),
                orderId,
                timestamp(data, "orderedAt"),
                message));
    }

    /**
     * payments.events carries no event name: shop branches on {@code status} and so does
     * this, translating it into one here so the rest of the service sees a name.
     */
    @KafkaListener(topics = "payments.events", groupId = "accounting.payments.consumer")
    public void onPaymentEvent(String message) {
        Map<String, Object> data = parse(message, "payment");
        String status = string(data.get("status"));
        if (status == null) {
            // PaymentIntentCreated and friends: no money has moved and there is nothing
            // to book or to store.
            log.debug("Payment event with no status, nothing to book");
            return;
        }
        String eventType = switch (status) {
            case "SUCCEEDED" -> "PaymentSucceeded";
            case "FAILED" -> "PaymentFailed";
            case "CANCELED" -> "PaymentCanceled";
            case "REFUNDED" -> "PaymentRefunded";
            case "REFUND_FAILED" -> "PaymentRefundFailed";
            default -> "Payment" + status;
        };
        String orderId = string(data.get("orderId"));
        // A top-up has no order and keys on the payment; a refund has no paymentId and
        // keys on the provider's refund id. Both must be unique per event or a redelivery
        // would look like a new one.
        String identity = firstNonNull(
                string(data.get("providerRefundId")), string(data.get("paymentId")), orderId);
        ingest(new IncomingEvent(
                "payments.events", eventType,
                key("payments.events", eventType, identity),
                orderId != null ? orderId : identity,
                timestamp(data, "refundedAt", "failedAt"),
                message));
    }

    /**
     * The recognition point rides this topic (§2.1). Delivery names its events
     * DELIVERY_CREATED and STATUS_UPDATE, so the meaning is in {@code status}.
     */
    @KafkaListener(topics = "delivery.events", groupId = "accounting.delivery.consumer")
    public void onDeliveryEvent(String message) {
        Map<String, Object> data = parse(message, "delivery");
        String status = string(data.get("status"));
        if (status == null) {
            log.debug("Delivery event with no status, nothing to book");
            return;
        }
        String eventType = "Delivery" + status.charAt(0) + status.substring(1).toLowerCase();
        String deliveryId = string(data.get("deliveryId"));
        String orderId = string(data.get("orderId"));
        ingest(new IncomingEvent(
                "delivery.events", eventType,
                key("delivery.events", eventType, deliveryId != null ? deliveryId : orderId),
                orderId,
                timestamp(data),
                message));
    }

    /**
     * Gift issuance and the funding split — the two facts with no other source (§4.2).
     * Keyed by transferId, which is unique per movement in balance's ledger.
     */
    @KafkaListener(topics = "balance.events", groupId = "accounting.balance.consumer")
    public void onBalanceEvent(String message) {
        Map<String, Object> data = parse(message, "balance");
        String eventType = string(data.get("eventType"));
        if (eventType == null) {
            throw new MalformedEventException("balance event with no eventType: " + message, null);
        }
        String transferId = string(data.get("transferId"));
        // Spent and Refunded belong to an order; a gift or a transfer belongs to a person.
        String aggregateId = firstNonNull(
                string(data.get("orderId")), string(data.get("username")), transferId);
        ingest(new IncomingEvent(
                "balance.events", eventType,
                key("balance.events", eventType, transferId),
                aggregateId,
                timestamp(data, "occurredAt"),
                message));
    }

    private void ingest(IncomingEvent event) {
        ingestService.ingest(event).block();
    }

    /**
     * Falls back to now when the producer dated nothing, and that is a real gap rather than
     * a tidy default: payments.events carries no timestamp on a successful payment, so a
     * payment consumed after a consumer outage is booked in the period it was consumed in,
     * not the one it happened in. Fixable only by the producer adding the field.
     */
    private static Instant timestamp(Map<String, Object> data, String... keys) {
        for (String key : keys) {
            Object value = data.get(key);
            if (value != null) {
                try {
                    return Instant.parse(value.toString());
                } catch (DateTimeParseException ignored) {
                    // Fall through to the next candidate rather than failing the record:
                    // an unparseable date is not a reason to lose the fact.
                }
            }
        }
        return Instant.now();
    }

    private static String key(String topic, String eventType, String identity) {
        return topic + ':' + eventType + ':' + identity;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String message, String kind) {
        try {
            return MAPPER.readValue(message, Map.class);
        } catch (Exception e) {
            throw new MalformedEventException("Failed to parse " + kind + " event: " + message, e);
        }
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
