package org.granitesecurity.payment.provider.stripe;

import org.granitesecurity.payment.domain.PaymentStatus;
import org.granitesecurity.payment.domain.RefundStatus;
import org.granitesecurity.payment.provider.ConfirmationMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The status-mapping logic that used to be buried in PaymentService and WebhookHandler.
 * Pure functions, so worth pinning: a wrong mapping here silently leaves an order in
 * the wrong state rather than throwing anywhere visible.
 */
class StripePaymentProviderTest {

    private final StripePaymentProvider provider = new StripePaymentProvider();

    @Test
    void intentStatusMapsOnlyTheStatesWeActOn() {
        assertThat(StripePaymentProvider.mapIntentStatus("succeeded")).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(StripePaymentProvider.mapIntentStatus("canceled")).isEqualTo(PaymentStatus.CANCELED);
        assertThat(StripePaymentProvider.mapIntentStatus("processing")).isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    void unactionableIntentStatusesMapToNullSoTheStoredStatusIsLeftAlone() {
        // requires_payment_method is the state a freshly created intent sits in, and the
        // state it returns to after a decline — mapping it to anything would overwrite
        // CREATED on every /sync.
        assertThat(StripePaymentProvider.mapIntentStatus("requires_payment_method")).isNull();
        assertThat(StripePaymentProvider.mapIntentStatus("requires_confirmation")).isNull();
        assertThat(StripePaymentProvider.mapIntentStatus("requires_action")).isNull();
        assertThat(StripePaymentProvider.mapIntentStatus("something_new_stripe_added")).isNull();
        assertThat(StripePaymentProvider.mapIntentStatus(null)).isNull();
    }

    @Test
    void webhookEventTypesMapToStatuses() {
        assertThat(StripePaymentProvider.mapEventType("payment_intent.succeeded")).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(StripePaymentProvider.mapEventType("payment_intent.payment_failed")).isEqualTo(PaymentStatus.FAILED);
        assertThat(StripePaymentProvider.mapEventType("payment_intent.canceled")).isEqualTo(PaymentStatus.CANCELED);
        assertThat(StripePaymentProvider.mapEventType("charge.updated")).isNull();
        assertThat(StripePaymentProvider.mapEventType(null)).isNull();
    }

    @Test
    void refundStatusesMap() {
        assertThat(StripePaymentProvider.mapRefundStatus("succeeded")).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(StripePaymentProvider.mapRefundStatus("pending")).isEqualTo(RefundStatus.PENDING);
        assertThat(StripePaymentProvider.mapRefundStatus("failed")).isEqualTo(RefundStatus.FAILED);
        assertThat(StripePaymentProvider.mapRefundStatus("canceled")).isEqualTo(RefundStatus.FAILED);
        assertThat(StripePaymentProvider.mapRefundStatus("requires_action")).isNull();
        assertThat(StripePaymentProvider.mapRefundStatus(null)).isNull();
    }

    @Test
    void refundEventTypesAreRecognisedAsRefundShaped() {
        // The value only marks the event as refund-shaped; parseWebhook then reads the
        // Refund object's own status, because refund.updated fires on every change.
        assertThat(StripePaymentProvider.mapRefundEventType("refund.updated")).isNotNull();
        assertThat(StripePaymentProvider.mapRefundEventType("refund.created")).isNotNull();
        assertThat(StripePaymentProvider.mapRefundEventType("charge.refund.updated")).isNotNull();
        assertThat(StripePaymentProvider.mapRefundEventType("refund.failed")).isEqualTo(RefundStatus.FAILED);
    }

    @Test
    void paymentEventsAreNotMistakenForRefundEvents() {
        assertThat(StripePaymentProvider.mapRefundEventType("payment_intent.succeeded")).isNull();
        assertThat(StripePaymentProvider.mapRefundEventType("charge.updated")).isNull();
        assertThat(StripePaymentProvider.mapRefundEventType(null)).isNull();
        // and the converse: a refund event must not map to a payment transition
        assertThat(StripePaymentProvider.mapEventType("refund.updated")).isNull();
    }

    @Test
    void describesItselfForTheProviderSelector() {
        assertThat(provider.name()).isEqualTo("stripe");
        assertThat(provider.confirmationMode()).isEqualTo(ConfirmationMode.CLIENT_SDK);
        assertThat(provider.supportedCurrencies()).contains("CHF", "USD", "EUR", "RON");
        // MDL is out of scope — Stripe does not settle it.
        assertThat(provider.supportedCurrencies()).doesNotContain("MDL");
    }
}
