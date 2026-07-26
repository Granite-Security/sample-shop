package org.granitesecurity.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreatePaymentIntentResponse(
    UUID id,
    Long orderId,
    String stripePaymentIntentId,
    String clientSecret,
    String status,
    BigDecimal amount,
    String currency,
    Instant createdAt,
    RefundInfo refund
) {
    public record RefundInfo(
        String stripeRefundId,
        BigDecimal amount,
        String status,
        Instant createdAt
    ) {}
}
