package org.granitesecurity.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Payment state for one order.
 *
 * <p><b>Deprecated aliases.</b> {@code stripePaymentIntentId} and {@code clientSecret}
 * duplicate {@code providerPaymentId} and {@code providerPayload.clientSecret}. They are
 * populated on every response so `ui-demo` — which is deployed, serving `app-chocolate`
 * and `app-multi` — keeps working while `ui-shop` migrates first.
 *
 * <p>They can only be populated while every enabled provider is CLIENT_SDK: a REDIRECT
 * provider has no client secret to put there. So the aliases expire on their own when a
 * second provider lands, which is the forcing function for migrating `ui-demo`
 * (refactor plan §2, step 4).
 */
public record CreatePaymentIntentResponse(
    UUID id,
    Long orderId,
    String provider,
    String providerPaymentId,
    Map<String, Object> providerPayload,
    String status,
    BigDecimal amount,
    String currency,
    Instant createdAt,
    RefundInfo refund,

    /** Deprecated alias for {@link #providerPaymentId()}. Removed once ui-demo migrates. */
    String stripePaymentIntentId,
    /** Deprecated alias for {@code providerPayload.clientSecret}. Removed once ui-demo migrates. */
    String clientSecret
) {
    public record RefundInfo(
        String providerRefundId,
        BigDecimal amount,
        String status,
        Instant createdAt,
        /** Deprecated alias for {@link #providerRefundId()}. Removed once ui-demo migrates. */
        String stripeRefundId
    ) {
        public static RefundInfo of(String providerRefundId, BigDecimal amount, String status, Instant createdAt) {
            return new RefundInfo(providerRefundId, amount, status, createdAt, providerRefundId);
        }
    }

    /** Builds a response with the deprecated aliases filled from the canonical fields. */
    public static CreatePaymentIntentResponse of(UUID id, Long orderId, String provider, String providerPaymentId,
                                                 Map<String, Object> providerPayload, String status,
                                                 BigDecimal amount, String currency, Instant createdAt,
                                                 RefundInfo refund) {
        Object secret = providerPayload == null ? null : providerPayload.get("clientSecret");
        return new CreatePaymentIntentResponse(
                id, orderId, provider, providerPaymentId, providerPayload, status, amount, currency, createdAt,
                refund,
                providerPaymentId,
                secret instanceof String s ? s : null);
    }
}
