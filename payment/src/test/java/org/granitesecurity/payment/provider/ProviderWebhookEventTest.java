package org.granitesecurity.payment.provider;

import org.granitesecurity.payment.domain.PaymentStatus;
import org.granitesecurity.payment.domain.RefundStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A webhook event is either a payment transition or a refund transition. Confusing
 * the two would apply a refund's status to a payment row, so the discrimination is
 * worth pinning.
 */
class ProviderWebhookEventTest {

    @Test
    void aPaymentEventIsNotARefundEvent() {
        var e = ProviderWebhookEvent.payment("evt_1", "payment_intent.succeeded", 42L,
                PaymentStatus.SUCCEEDED, "pi_1");

        assertThat(e.isPaymentTransition()).isTrue();
        assertThat(e.isRefundTransition()).isFalse();
    }

    @Test
    void aRefundEventIsNotAPaymentEvent() {
        var e = ProviderWebhookEvent.refund("evt_2", "refund.updated", RefundStatus.FAILED, "re_1", "pi_1");

        assertThat(e.isRefundTransition()).isTrue();
        assertThat(e.isPaymentTransition()).isFalse();
        assertThat(e.refundStatus()).isEqualTo(RefundStatus.FAILED);
    }

    @Test
    void aRefundEventNeedsNoOrderId() {
        // Refunds are looked up by the provider's refund id: the Refund object carries
        // no order metadata, only the payment intent it belongs to.
        var e = ProviderWebhookEvent.refund("evt_3", "refund.updated", RefundStatus.SUCCEEDED, "re_2", "pi_2");

        assertThat(e.orderId()).isNull();
        assertThat(e.isRefundTransition()).isTrue();
    }

    @Test
    void anUnmappableEventIsNeither() {
        var e = ProviderWebhookEvent.payment("evt_4", "charge.updated", null, null, null);

        assertThat(e.isPaymentTransition()).isFalse();
        assertThat(e.isRefundTransition()).isFalse();
    }
}
