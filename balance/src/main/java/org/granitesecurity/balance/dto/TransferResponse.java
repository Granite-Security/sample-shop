package org.granitesecurity.balance.dto;

import java.math.BigDecimal;

/** The result of a movement, and what a retry with the same key replays. */
public record TransferResponse(
        String transferId,
        String from,
        String to,
        long amountMinor,
        BigDecimal amountChf
) {}
