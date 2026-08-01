package org.granitesecurity.payment.dto;

import java.math.BigDecimal;

/**
 * @param provider provider id from {@code GET /api/payments/providers}. Optional while
 *                 one provider is enabled; once several are, omitting it is a 400 rather
 *                 than a silent choice made on the shopper's behalf.
 */
public record CreatePaymentIntentRequest(
    Long orderId,
    BigDecimal total,
    String currency,
    String username,
    String provider
) {}
