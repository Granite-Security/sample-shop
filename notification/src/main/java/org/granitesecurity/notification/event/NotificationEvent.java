package org.granitesecurity.notification.event;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

/**
 * A domain fact consumed off Kafka. Deliberately flat and schema-light: producers
 * publish what happened, never rendered text (see §3 of the design doc).
 *
 * @param raw every field as received, used as the template model so adding a field
 *            to an event needs no change here.
 */
public record NotificationEvent(
        UUID id,
        String type,
        String username,
        String email,
        Instant occurredAt,
        Map<String, Object> raw) {

    public static NotificationEvent from(Map<String, Object> data, String rawMessage) {
        String type = string(data.get("type"));
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("event has no 'type'");
        }
        return new NotificationEvent(
                parseId(data.get("id"), rawMessage),
                type,
                string(data.get("username")),
                string(data.get("email")),
                parseInstant(data.get("occurredAt")),
                data);
    }

    /**
     * Events should carry an {@code id} for dedupe. When one doesn't — hand-produced
     * test messages, mainly — derive it from the message bytes, so producing the same
     * message twice still deduplicates instead of sending twice.
     */
    private static UUID parseId(Object value, String rawMessage) {
        String id = string(value);
        if (id != null) {
            try {
                return UUID.fromString(id);
            } catch (IllegalArgumentException ignored) {
                // fall through to the content-derived id
            }
        }
        return UUID.nameUUIDFromBytes(rawMessage.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A missing timestamp is treated as "just happened" rather than dropped: the
     * staleness guard exists to suppress genuinely old replays, and failing closed
     * on a producer that forgot the field would silently stop mail instead.
     */
    private static Instant parseInstant(Object value) {
        String text = string(value);
        if (text == null) {
            return Instant.now();
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return Instant.now();
        }
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
