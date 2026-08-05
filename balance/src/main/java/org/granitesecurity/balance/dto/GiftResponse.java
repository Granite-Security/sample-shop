package org.granitesecurity.balance.dto;

import java.math.BigDecimal;

/** The result of unbacked issuance, and what a retry with the same key replays. */
public record GiftResponse(
        String transferId,
        String username,
        long amountMinor,
        BigDecimal amountChf,
        String grantedBy
) {}
