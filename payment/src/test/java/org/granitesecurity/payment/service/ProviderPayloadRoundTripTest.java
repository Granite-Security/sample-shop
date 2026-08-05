package org.granitesecurity.payment.service;

import org.granitesecurity.payment.domain.PaymentStatus;
import org.granitesecurity.payment.provider.ProviderIntent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a provider hands back has to survive the trip to {@code provider_payload} and
 * out again to the browser.
 *
 * <p>This exists because it silently did not. {@code ProviderIntent.redirectUrl} was a
 * top-level field that nothing ever read: {@code toProviderPayload} serialized only the
 * payload map, so a redirect provider's approve link was dropped on save while
 * {@code RedirectPaymentWidget} sat waiting for {@code payload.redirectUrl}. Nothing
 * threw — the shopper would simply have got a payment page with no way to pay.
 */
class ProviderPayloadRoundTripTest {

    @Test
    void redirectUrlReachesTheStoredPayload() {
        var intent = new ProviderIntent("5O190127TN364715T", null, "PAYER_ACTION_REQUIRED",
                Map.of(), "https://www.sandbox.paypal.com/checkoutnow?token=5O190127TN364715T", null);

        String stored = PaymentService.toProviderPayload(intent);

        assertThat(PaymentService.payloadMap(stored))
                .containsEntry("redirectUrl", "https://www.sandbox.paypal.com/checkoutnow?token=5O190127TN364715T");
    }

    @Test
    void clientSecretStillRoundTripsForClientSdkProviders() {
        var intent = new ProviderIntent("pi_123", PaymentStatus.CREATED, "requires_payment_method",
                Map.of("clientSecret", "pi_123_secret_456"), null, null);

        assertThat(PaymentService.payloadMap(PaymentService.toProviderPayload(intent)))
                .containsEntry("clientSecret", "pi_123_secret_456")
                .doesNotContainKey("redirectUrl");
    }

    @Test
    void anIntentCarryingNeitherStoresNothing() {
        var intent = new ProviderIntent("x", null, "created", Map.of(), null, null);
        assertThat(PaymentService.toProviderPayload(intent)).isNull();
    }

    @Test
    void aBareStringPayloadIsStillReadAsAClientSecret() {
        // Rows written before migration 005 held the secret unwrapped. The migration
        // rewrote them, but a row written by an older instance mid-rollout would not be.
        assertThat(PaymentService.payloadMap("pi_123_secret_456"))
                .containsEntry("clientSecret", "pi_123_secret_456");
    }
}
