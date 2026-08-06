package org.granitesecurity.balance.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One leg of one movement, for the treasury feed. Two rows share a transferId
 * and their amounts sum to zero — that pairing is the whole point of showing it.
 */
public record LedgerEntryView(
        Long id,
        String transferId,
        Long accountId,
        long amountMinor,
        BigDecimal amountChf,
        String kind,
        String reference,
        String memo,
        Instant createdAt
) {}
