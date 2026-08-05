package org.granitesecurity.balance.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** One ledger entry as its owner sees it. Signed: negative means money left. */
public record TransactionResponse(
        Long id,
        String transferId,
        long amountMinor,
        BigDecimal amountChf,
        String kind,
        String reference,
        String memo,
        Instant createdAt
) {}
