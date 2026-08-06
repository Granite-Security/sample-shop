package org.granitesecurity.balance.dto;

import java.math.BigDecimal;

/**
 * One account on the treasury page. A HOUSE account's negative balance is the
 * money it has issued, so the sign is meaningful and must not be hidden
 * (docs/finance/finance.md §2).
 */
public record AccountView(
        Long id,
        String username,
        String kind,
        long balanceMinor,
        BigDecimal balanceChf
) {}
