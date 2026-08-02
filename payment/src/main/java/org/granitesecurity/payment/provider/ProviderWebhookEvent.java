package org.granitesecurity.payment.provider;

import org.granitesecurity.payment.domain.PaymentStatus;
import org.granitesecurity.payment.domain.RefundStatus;

/**
 * A verified inbound webhook, translated out of the provider's event shape.
 *
 * @param eventId    the provider's event id, used for dedupe
 * @param eventType  the provider's own type string, recorded and logged
 * @param orderId    resolved from provider metadata; null when the event carries none,
 *                   in which case there is nothing for us to update
 * @param status     mapped payment transition; null when the event is not one
 * @param providerPaymentId the provider's payment id, when the event carries one
 * @param refundStatus      mapped refund transition; null when the event is not one
 * @param providerRefundId  the provider's refund id, when the event carries one
 *
 * <p>An event is either a payment transition or a refund transition, never both.
 * Refund events are the only signal that arrives <b>after</b> we have stopped
 * looking: a refund is recorded SUCCEEDED when the provider accepts it, and can
 * still fail later at the bank.
 */
public record ProviderWebhookEvent(
        String eventId,
        String eventType,
        Long orderId,
        PaymentStatus status,
        String providerPaymentId,
        RefundStatus refundStatus,
        String providerRefundId) {

    /** A payment transition we act on. */
    public boolean isPaymentTransition() {
        return orderId != null && status != null;
    }

    /** A refund transition we act on. Keyed by refund id, so it needs no order id. */
    public boolean isRefundTransition() {
        return providerRefundId != null && refundStatus != null;
    }

    /** Convenience for a payment-only event, as produced before refunds were handled. */
    public static ProviderWebhookEvent payment(String eventId, String eventType, Long orderId,
                                               PaymentStatus status, String providerPaymentId) {
        return new ProviderWebhookEvent(eventId, eventType, orderId, status, providerPaymentId, null, null);
    }

    public static ProviderWebhookEvent refund(String eventId, String eventType, RefundStatus refundStatus,
                                              String providerRefundId, String providerPaymentId) {
        return new ProviderWebhookEvent(eventId, eventType, null, null, providerPaymentId,
                refundStatus, providerRefundId);
    }
}
