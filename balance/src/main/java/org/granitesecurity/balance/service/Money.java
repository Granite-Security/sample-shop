package org.granitesecurity.balance.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * CHF ↔ rappen. The ledger only ever holds {@code long} rappen (D3); decimals exist
 * at the API edge and nowhere else.
 *
 * <p>Deliberately narrower than payment's {@code MinorUnits}: this service is
 * single-currency, so there is no currency argument to get wrong. Sub-rappen amounts
 * are rejected rather than rounded — one arriving here is a bad request, not an
 * amount to guess at.
 */
public final class Money {

    private Money() {
    }

    /** @throws IllegalArgumentException if null, negative, zero or finer than a rappen */
    public static long toRappen(BigDecimal chf) {
        if (chf == null) {
            throw new IllegalArgumentException("Amount is required");
        }
        if (chf.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive: " + chf);
        }
        if (chf.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Amount is finer than a rappen: " + chf);
        }
        // longValueExact: an amount too large for a long throws rather than wrapping
        // silently into a negative balance.
        return chf.movePointRight(2).longValueExact();
    }

    public static BigDecimal toChf(long rappen) {
        return BigDecimal.valueOf(rappen).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY);
    }
}
