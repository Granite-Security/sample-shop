package org.granitesecurity.payment.dto;

import java.math.BigDecimal;

public record CreatePaymentIntentRequest(
    Long orderId,
    BigDecimal total,
    String currency,
    String username
) {}
