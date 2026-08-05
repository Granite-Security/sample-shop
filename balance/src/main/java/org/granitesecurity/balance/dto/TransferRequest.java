package org.granitesecurity.balance.dto;

import java.math.BigDecimal;

/**
 * Sending money to another user. The sender is the JWT subject and is never a
 * field here — the same rule every other service in this platform follows.
 *
 * @param idempotencyKey optional; supply one and a retry replays the original
 *                       result instead of sending twice
 */
public record TransferRequest(
        String to,
        BigDecimal amountChf,
        String memo,
        String idempotencyKey
) {}
