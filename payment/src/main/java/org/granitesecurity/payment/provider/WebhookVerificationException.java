package org.granitesecurity.payment.provider;

/** Signature missing, malformed or not matching the configured secret. */
public class WebhookVerificationException extends Exception {
    public WebhookVerificationException(String message) {
        super(message);
    }

    public WebhookVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
