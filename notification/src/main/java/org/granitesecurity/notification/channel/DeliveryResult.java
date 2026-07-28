package org.granitesecurity.notification.channel;

/**
 * Outcome of one delivery attempt. Never thrown — a provider failure must not
 * propagate, because there is nothing useful to do with it beyond recording it:
 * the user-facing action (password change, registration) has already committed.
 */
public record DeliveryResult(Status status, String providerMessageId, String error) {

    public enum Status {
        SENT,
        FAILED,
        /** Channel is switched off (e.g. RESEND_API_KEY unset) — not an error. */
        SKIPPED_DISABLED
    }

    public static DeliveryResult sent(String providerMessageId) {
        return new DeliveryResult(Status.SENT, providerMessageId, null);
    }

    public static DeliveryResult failed(String error) {
        return new DeliveryResult(Status.FAILED, null, error);
    }

    public static DeliveryResult skippedDisabled() {
        return new DeliveryResult(Status.SKIPPED_DISABLED, null, null);
    }
}
