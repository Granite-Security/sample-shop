package org.granitesecurity.payment.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deprecated aliases are what keep `ui-demo` working while `ui-shop` migrates, so
 * they are a contract, not a convenience. If these break, a deployed frontend breaks.
 */
class CreatePaymentIntentResponseTest {

    @Test
    void aliasesMirrorTheCanonicalFields() {
        var response = CreatePaymentIntentResponse.of(
                UUID.randomUUID(), 42L, "stripe", "pi_123",
                Map.of("clientSecret", "pi_123_secret_abc"),
                "CREATED", new BigDecimal("10.00"), "CHF", Instant.now(), null);

        assertThat(response.stripePaymentIntentId()).isEqualTo(response.providerPaymentId()).isEqualTo("pi_123");
        assertThat(response.clientSecret()).isEqualTo("pi_123_secret_abc");
        assertThat(response.providerPayload()).containsEntry("clientSecret", "pi_123_secret_abc");
    }

    @Test
    void aRedirectStylePayloadLeavesTheClientSecretAliasNull() {
        // The alias is only expressible while providers are CLIENT_SDK. A redirect
        // provider has no secret to put there — which is what eventually retires it.
        var response = CreatePaymentIntentResponse.of(
                UUID.randomUUID(), 42L, "noop", "noop_1",
                Map.of("redirectUrl", "https://example.invalid/pay/noop_1"),
                "CREATED", new BigDecimal("10.00"), "CHF", Instant.now(), null);

        assertThat(response.clientSecret()).isNull();
        assertThat(response.providerPaymentId()).isEqualTo("noop_1");
        assertThat(response.stripePaymentIntentId()).isEqualTo("noop_1");
    }

    @Test
    void anAbsentPayloadIsNotAnError() {
        var response = CreatePaymentIntentResponse.of(
                UUID.randomUUID(), 42L, "stripe", "pi_123", null,
                "CREATED", new BigDecimal("10.00"), "CHF", Instant.now(), null);

        assertThat(response.providerPayload()).isNull();
        assertThat(response.clientSecret()).isNull();
    }

    @Test
    void refundAliasMirrorsProviderRefundId() {
        var refund = CreatePaymentIntentResponse.RefundInfo.of(
                "re_123", new BigDecimal("10.00"), "SUCCEEDED", Instant.now());

        assertThat(refund.stripeRefundId()).isEqualTo(refund.providerRefundId()).isEqualTo("re_123");
    }
}
