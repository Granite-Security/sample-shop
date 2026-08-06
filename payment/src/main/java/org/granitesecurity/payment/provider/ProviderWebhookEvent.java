package org.granitesecurity.payment.provider;

import org.granitesecurity.payment.domain.PaymentStatus;
import org.granitesecurity.payment.domain.RefundStatus;

/**
 * A verified inbound webhook, translated out of the provider's event shape.
 *
 * @param eventId    the provider's event id, used for dedupe
 * @param eventType  the provider's own type string, recorded and logged
 * @param orderId    resolved from provider metadata; null when the event carries none —
 *                   a top-up has no order, so this is null for every one of them
 * @param reference  the unique handle we put on the intent: the order id for an order,
 *                   the payment id for a top-up. <b>This, not orderId, is what identifies
 *                   the payment.</b> Before it existed, a top-up webhook resolved to no
 *                   order and was silently skipped with a 200, so the provider never
 *                   retried and the balance was never credited
 * @param status     mapped payment transition; null when the event is not one
 * @param providerPaymentId the provider's payment id, when the event carries one
 * @param refundStatus      mapped refund transition; null when the event is not one
 * @param providerRefundId  the provider's refund id, when the event carries one
 *
 * <p>An event is either a payment transition or a refund transition, never both.
 * Refund events are the only signal that arrives <b>after</b> we have stopped
 * looking: a refund is recorded SUCCEEDED when the provider accepts it, and can
 * still fail later at the bank.
 *
 * @param requiresFinalization the shopper approved but no money has moved yet, and the
 *                             provider is waiting for us to take it. Carries no
 *                             {@code status} — approval is not payment. Only a
 *                             {@link RedirectPaymentProvider} ever produces this.
 */
public record ProviderWebhookEvent(
        String eventId,
        String eventType,
        Long orderId,
        String reference,
        PaymentStatus status,
        String providerPaymentId,
        RefundStatus refundStatus,
        String providerRefundId,
        boolean requiresFinalization) {

    /** A payment transition we act on. Keyed on reference, since a top-up has no order. */
    public boolean isPaymentTransition() {
        return (orderId != null || reference != null) && status != null;
    }

    /**
     * True when this event belongs to a payment with no order behind it — a top-up.
     * Resolving it means looking the payment up by id, not by order.
     */
    public boolean isOrderless() {
        return orderId == null && reference != null;
    }

    /** A refund transition we act on. Keyed by refund id, so it needs no order id. */
    public boolean isRefundTransition() {
        return providerRefundId != null && refundStatus != null;
    }

    /** Convenience for a payment-only event, as produced before refunds were handled. */
    public static ProviderWebhookEvent payment(String eventId, String eventType, Long orderId,
                                               String reference, PaymentStatus status,
                                               String providerPaymentId) {
        return new ProviderWebhookEvent(eventId, eventType, orderId, reference, status,
                providerPaymentId, null, null, false);
    }

    public static ProviderWebhookEvent refund(String eventId, String eventType, RefundStatus refundStatus,
                                              String providerRefundId, String providerPaymentId) {
        return new ProviderWebhookEvent(eventId, eventType, null, null, null, providerPaymentId,
                refundStatus, providerRefundId, false);
    }

    /**
     * The shopper approved the payment and the provider is waiting to be told to take
     * the money. This is the recovery path for a shopper who approves and then closes
     * the tab without ever hitting the return URL.
     */
    public static ProviderWebhookEvent approval(String eventId, String eventType, Long orderId,
                                                String reference, String providerPaymentId) {
        return new ProviderWebhookEvent(eventId, eventType, orderId, reference, null,
                providerPaymentId, null, null, true);
    }
}
