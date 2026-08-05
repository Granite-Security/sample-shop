package org.granitesecurity.payment.provider.paypal;

import org.granitesecurity.payment.domain.PaymentStatus;
import org.granitesecurity.payment.domain.RefundStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The status mappings, pinned as pure functions.
 *
 * <p>These matter more than Stripe's equivalents because PayPal has a state that
 * <b>looks</b> paid and is not: an order sits at {@code APPROVED} once the shopper says
 * yes, with the money still in their account. Getting that one wrong does not throw
 * anywhere — it publishes {@code PaymentReceived} and ships goods for free.
 */
class PayPalPaymentProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode json(String raw) {
        return MAPPER.readTree(raw);
    }

    @Test
    void approvedIsNotPaid() {
        // The whole point. APPROVED means the shopper agreed; the money moves on capture.
        JsonNode order = json("{\"id\":\"5O1\",\"status\":\"APPROVED\"}");
        assertThat(PayPalPaymentProvider.mapOrderStatus("APPROVED", order)).isNull();
    }

    @Test
    void statesBeforeApprovalAreAlsoNoTransition() {
        JsonNode order = json("{\"id\":\"5O1\"}");
        assertThat(PayPalPaymentProvider.mapOrderStatus("CREATED", order)).isNull();
        assertThat(PayPalPaymentProvider.mapOrderStatus("SAVED", order)).isNull();
        assertThat(PayPalPaymentProvider.mapOrderStatus("PAYER_ACTION_REQUIRED", order)).isNull();
        assertThat(PayPalPaymentProvider.mapOrderStatus("SOMETHING_PAYPAL_ADDED_LATER", order)).isNull();
        assertThat(PayPalPaymentProvider.mapOrderStatus(null, order)).isNull();
    }

    @Test
    void completedOrderDefersToItsCaptureStatus() {
        // An order can be COMPLETED while the capture under it was declined — trusting
        // the order alone would mark that payment succeeded.
        JsonNode declined = json("""
                {"id":"5O1","status":"COMPLETED","purchase_units":[
                  {"payments":{"captures":[{"id":"3C7","status":"DECLINED"}]}}]}""");
        assertThat(PayPalPaymentProvider.mapOrderStatus("COMPLETED", declined))
                .isEqualTo(PaymentStatus.FAILED);

        JsonNode completed = json("""
                {"id":"5O1","status":"COMPLETED","purchase_units":[
                  {"payments":{"captures":[{"id":"3C7","status":"COMPLETED"}]}}]}""");
        assertThat(PayPalPaymentProvider.mapOrderStatus("COMPLETED", completed))
                .isEqualTo(PaymentStatus.SUCCEEDED);

        JsonNode pending = json("""
                {"id":"5O1","status":"COMPLETED","purchase_units":[
                  {"payments":{"captures":[{"id":"3C7","status":"PENDING"}]}}]}""");
        assertThat(PayPalPaymentProvider.mapOrderStatus("COMPLETED", pending))
                .isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    void voidedOrderIsCanceled() {
        assertThat(PayPalPaymentProvider.mapOrderStatus("VOIDED", json("{\"id\":\"5O1\"}")))
                .isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    void captureEventTypesMapToStatuses() {
        assertThat(PayPalPaymentProvider.mapCaptureEventType("PAYMENT.CAPTURE.COMPLETED"))
                .isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(PayPalPaymentProvider.mapCaptureEventType("PAYMENT.CAPTURE.DENIED"))
                .isEqualTo(PaymentStatus.FAILED);
        // Approval is handled as a finalization request, never as a payment transition,
        // so it must not resolve to a status here.
        assertThat(PayPalPaymentProvider.mapCaptureEventType("CHECKOUT.ORDER.APPROVED")).isNull();
        assertThat(PayPalPaymentProvider.mapCaptureEventType(null)).isNull();
    }

    @Test
    void refundEventsCoverBothTheVoluntaryAndForcedCase() {
        assertThat(PayPalPaymentProvider.mapRefundEventType("PAYMENT.CAPTURE.REFUNDED"))
                .isEqualTo(RefundStatus.SUCCEEDED);
        // A reversal is PayPal taking the money back on the shopper's behalf; it lands
        // in the same place as a refund we issued.
        assertThat(PayPalPaymentProvider.mapRefundEventType("PAYMENT.CAPTURE.REVERSED"))
                .isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(PayPalPaymentProvider.mapRefundEventType("PAYMENT.CAPTURE.COMPLETED")).isNull();
    }

    @Test
    void refundStatusMapsOnlyWhatWeActOn() {
        assertThat(PayPalPaymentProvider.mapRefundStatus("COMPLETED")).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(PayPalPaymentProvider.mapRefundStatus("FAILED")).isEqualTo(RefundStatus.FAILED);
        assertThat(PayPalPaymentProvider.mapRefundStatus("CANCELLED")).isEqualTo(RefundStatus.FAILED);
        assertThat(PayPalPaymentProvider.mapRefundStatus("PENDING")).isEqualTo(RefundStatus.PENDING);
        assertThat(PayPalPaymentProvider.mapRefundStatus(null)).isNull();
    }
}
