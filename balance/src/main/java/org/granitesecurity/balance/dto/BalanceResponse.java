package org.granitesecurity.balance.dto;

import java.math.BigDecimal;

/**
 * {@code balanceChf} is derived from {@code balanceMinor}, never stored — the
 * ledger holds rappen only (D3). It can be negative when credit has been extended.
 */
public record BalanceResponse(
        String username,
        long balanceMinor,
        BigDecimal balanceChf,
        String currency
) {}
