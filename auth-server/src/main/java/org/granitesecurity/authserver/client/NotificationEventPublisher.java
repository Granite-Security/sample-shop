package org.granitesecurity.authserver.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes identity events to {@code notifications.events} for the notification
 * service to turn into email (or, later, SMS/WhatsApp).
 *
 * <p><strong>Deliberately fire-and-forget, with no outbox.</strong> Unlike shop,
 * payment and delivery, this producer accepts message loss. The three events here are
 * a welcome mail, a password-changed confirmation, and a password-reset link; the
 * first two are courtesy, and a lost reset link is recovered by the user clicking
 * "forgot password" again, which mints a fresh token. Adding an outbox table, a JPA
 * entity and a polling relay to the one servlet/JPA service in the platform is not
 * worth that. Do not "fix" this into the outbox pattern — see
 * docs/notification/notification-microservice.md §2.
 *
 * <p>Three properties must survive any edit here:
 * <ul>
 *   <li>{@code @Async} — the send stays off the request thread.</li>
 *   <li>Every failure is caught and logged. A broker hiccup must never surface to a
 *       user whose password change has already committed.</li>
 *   <li>{@code max.block.ms} is 2s (see KafkaProducerConfig). Left at its 60s default,
 *       a dead broker would pin an async worker for a minute per send — the accepted
 *       failure mode is losing messages, not stalling threads.</li>
 * </ul>
 *
 * <p>Callers invoke this from an {@code afterCommit} synchronization, so a rolled-back
 * transaction never emits an event.
 */
@Component
public class NotificationEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventPublisher.class);
    private static final String TOPIC = "notifications.events";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KafkaTemplate<String, String> kafkaTemplate;

    public NotificationEventPublisher(ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider) {
        // Absent when no broker is configured — the service still boots and simply
        // sends nothing, matching how the outbox relays elsewhere degrade.
        this.kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
    }

    @Async
    public void publishPasswordChanged(String username, String email) {
        publish("PasswordChanged", username, email, Map.of());
    }

    /**
     * Publishes the raw reset token, not a rendered link: the notification service
     * builds the URL from its own configured frontend origin (D3). The topic keeps a
     * 1-hour retention specifically because this payload exists.
     */
    @Async
    public void publishPasswordResetRequested(String username, String email, String resetToken, Instant expiresAt) {
        publish("PasswordResetRequested", username, email, Map.of(
                "resetToken", resetToken,
                "expiresAt", expiresAt.toString()));
    }

    @Async
    public void publishUserRegistered(String username, String email) {
        publish("UserRegistered", username, email, Map.of());
    }

    private void publish(String type, String username, String email, Map<String, Object> extra) {
        if (kafkaTemplate == null) {
            log.warn("KafkaTemplate not available — dropping {} for {}", type, username);
            return;
        }
        try {
            Map<String, Object> payload = new HashMap<>(extra);
            payload.put("id", UUID.randomUUID().toString());
            payload.put("type", type);
            payload.put("username", username);
            payload.put("email", email);
            payload.put("occurredAt", Instant.now().toString());

            kafkaTemplate.send(TOPIC, username, MAPPER.writeValueAsString(payload));
            log.info("published {} for {}", type, username);
        } catch (Exception ex) {
            log.warn("failed to publish {} for {}: {}", type, username, ex.getMessage());
        }
    }
}
