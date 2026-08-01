package org.granitesecurity.payment.provider;

import org.granitesecurity.payment.domain.PaymentStatus;

import java.util.Map;

/**
 * A payment in progress at a provider.
 *
 * @param providerPaymentId the provider's id, persisted in payment.provider_payment_id
 * @param status            mapped to our vocabulary; <b>null means "no transition"</b> —
 *                          the provider reported a state we deliberately do not act on
 *                          (Stripe's requires_payment_method, for one), and the caller
 *                          must leave the stored status alone
 * @param rawStatus         the provider's own string, for logs and diagnosis
 * @param payload           what the frontend needs to complete the payment. For CLIENT_SDK
 *                          this holds the client secret; for REDIRECT it is typically empty
 *                          and {@link #redirectUrl} carries the work. Never assume a shape.
 * @param redirectUrl       where to send the shopper; null for CLIENT_SDK providers
 * @param declineReason     a decline the shopper must be shown, as data rather than only
 *                          as an exception; null when there is nothing to explain
 */
public record ProviderIntent(
        String providerPaymentId,
        PaymentStatus status,
        String rawStatus,
        Map<String, Object> payload,
        String redirectUrl,
        String declineReason) {

    public ProviderIntent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    /** The client secret for CLIENT_SDK providers, or null if this payload carries none. */
    public String clientSecret() {
        Object v = payload.get("clientSecret");
        return v instanceof String s ? s : null;
    }
}
