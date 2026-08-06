package org.granitesecurity.payment.provider;

import org.granitesecurity.payment.domain.PaymentStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The distinction this type has to carry: an order payment resolves by order id,
 * a top-up by reference. Before `reference` existed, a top-up webhook resolved to
 * nothing and was skipped with a 200 — the provider never retried, and money was
 * captured but never credited (docs/finance/finance.md §6.1).
 */
class ProviderWebhookEventTest {

    private static final String TOPUP_REFERENCE = "16c10f9e-7f50-4c16-a751-0bc638ddbd86";

    @Test
    void anOrderPaymentIsATransitionAndIsNotOrderless() {
        var event = ProviderWebhookEvent.payment(
                "evt_1", "PAYMENT.CAPTURE.COMPLETED", 42L, "42",
                PaymentStatus.SUCCEEDED, "pi_1");

        assertTrue(event.isPaymentTransition());
        assertFalse(event.isOrderless(), "an order payment has an order id");
    }

    @Test
    void aTopupIsATransitionEvenWithNoOrderId() {
        var event = ProviderWebhookEvent.payment(
                "evt_2", "PAYMENT.CAPTURE.COMPLETED", null, TOPUP_REFERENCE,
                PaymentStatus.SUCCEEDED, "pi_2");

        // This is the assertion that would have caught the bug: before `reference`,
        // isPaymentTransition() required an order id and this was false.
        assertTrue(event.isPaymentTransition(), "a top-up is still a payment transition");
        assertTrue(event.isOrderless());
    }

    @Test
    void anEventWithNeitherIdentifierIsNotATransition() {
        var event = ProviderWebhookEvent.payment(
                "evt_3", "PAYMENT.CAPTURE.COMPLETED", null, null,
                PaymentStatus.SUCCEEDED, "pi_3");

        // Nothing to resolve it by: skipped rather than retried forever.
        assertFalse(event.isPaymentTransition());
        assertFalse(event.isOrderless());
    }

    @Test
    void aRefundNeedsNoOrderOrReference() {
        var event = ProviderWebhookEvent.refund(
                "evt_4", "PAYMENT.CAPTURE.REFUNDED",
                org.granitesecurity.payment.domain.RefundStatus.SUCCEEDED, "re_1", "pi_1");

        assertTrue(event.isRefundTransition(), "refunds are keyed by refund id");
        assertFalse(event.isPaymentTransition());
    }

    @Test
    void anApprovalCarriesItsReferenceForFinalization() {
        var topupApproval = ProviderWebhookEvent.approval(
                "evt_5", "CHECKOUT.ORDER.APPROVED", null, TOPUP_REFERENCE, "pp_1");

        // The path that rescues a shopper who approves and closes the tab. Without a
        // reference there is nothing to finalize a top-up against.
        assertTrue(topupApproval.requiresFinalization());
        assertTrue(topupApproval.isOrderless());
    }
}
