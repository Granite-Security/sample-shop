package org.granitesecurity.balance.dto;

import java.math.BigDecimal;

/**
 * An intent as payment sees it. `status` is balance's own vocabulary —
 * CREATED | CAPTURED | FAILED | REFUNDED — which BalanceProvider maps into
 * PaymentStatus. CREATED means nothing has moved yet.
 */
public record IntentResponse(
        String id,
        String username,
        long amountMinor,
        BigDecimal amountChf,
        Long orderId,
        String status,
        String transferId,
        String declineReason
) {}
