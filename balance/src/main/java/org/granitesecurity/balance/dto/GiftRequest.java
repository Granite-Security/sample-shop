package org.granitesecurity.balance.dto;

import java.math.BigDecimal;

/**
 * An admin conjuring credit. The acting admin comes from the JWT, never the body —
 * the same rule every other service here follows.
 */
public record GiftRequest(
        String username,
        BigDecimal amountChf,
        String reason,
        String idempotencyKey
) {}
