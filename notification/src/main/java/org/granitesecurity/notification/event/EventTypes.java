package org.granitesecurity.notification.event;

import java.time.Duration;
import java.util.Map;

/**
 * Identity event types produced by auth-server, and how stale each may be before
 * it is dropped rather than delivered.
 *
 * <p>Kafka retention deletes messages from the log; it does <em>not</em> stop a
 * consumer from acting on one that is still there. A consumer that was down for
 * 50 minutes would otherwise come back and mail an unprompted "reset your
 * password" carrying an already-expired token. See §4.1 of the design doc.
 */
public final class EventTypes {

    public static final String PASSWORD_CHANGED = "PasswordChanged";
    public static final String PASSWORD_RESET_REQUESTED = "PasswordResetRequested";
    public static final String USER_REGISTERED = "UserRegistered";

    private static final Duration DEFAULT_MAX_AGE = Duration.ofHours(24);

    private static final Map<String, Duration> MAX_AGE = Map.of(
            // Tightest window: the token itself lives 30 minutes, and a late reset
            // email is actively confusing rather than merely useless.
            PASSWORD_RESET_REQUESTED, Duration.ofMinutes(5),
            PASSWORD_CHANGED, Duration.ofHours(1),
            USER_REGISTERED, Duration.ofHours(24));

    private EventTypes() {}

    public static Duration maxAge(String eventType) {
        return MAX_AGE.getOrDefault(eventType, DEFAULT_MAX_AGE);
    }
}
