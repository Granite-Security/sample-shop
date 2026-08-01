package org.granitesecurity.payment.provider;

/**
 * How the shopper completes a payment. The frontend switches on this rather than on
 * provider name, so a second redirect-shaped provider needs a selector entry and no
 * new component.
 */
public enum ConfirmationMode {
    /** Provider JS SDK confirms in-page (Stripe Elements). Payload carries a client secret. */
    CLIENT_SDK,
    /** Shopper is sent to the provider and returns to /api/payments/return/{provider}. */
    REDIRECT
}
