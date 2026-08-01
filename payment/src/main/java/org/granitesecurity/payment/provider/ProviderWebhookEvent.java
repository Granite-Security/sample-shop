package org.granitesecurity.payment.provider;

import org.granitesecurity.payment.domain.PaymentStatus;

/**
 * A verified inbound webhook, translated out of the provider's event shape.
 *
 * @param eventId    the provider's event id, used for dedupe
 * @param eventType  the provider's own type string, recorded and logged
 * @param orderId    resolved from provider metadata; null when the event carries none,
 *                   in which case there is nothing for us to update
 * @param status     mapped; null means the event is not a status change we act on
 * @param providerPaymentId the provider's payment id, when the event carries one
 */
public record ProviderWebhookEvent(
        String eventId,
        String eventType,
        Long orderId,
        PaymentStatus status,
        String providerPaymentId) {

    /** True when this event should move a payment row. */
    public boolean isActionable() {
        return orderId != null && status != null;
    }
}
