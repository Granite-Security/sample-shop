package org.granitesecurity.payment.service;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Converts a decimal amount to the integer minor units payment providers charge in.
 *
 * <p>Every currency the shop supports (USD, EUR, RON, CHF) is two-decimal, so the
 * conversion is always x100. Zero-decimal currencies are rejected outright rather
 * than silently overcharged by a factor of 100 — adding one means teaching this
 * class about exponents, not passing a different code through it.
 *
 * <p>Sub-cent amounts are rejected rather than rounded: the shop has no prices that
 * need them, so one arriving here means a bad price, not an amount to guess at.
 */
public final class MinorUnits {

    /** Common zero-decimal ISO codes, rejected explicitly so the failure is loud. */
    private static final Set<String> ZERO_DECIMAL = Set.of("JPY", "KRW", "VND", "CLP", "ISK", "XOF", "XAF");

    private MinorUnits() {
    }

    /**
     * @param amount   a non-null, non-negative amount with at most two decimal places
     * @param currency ISO 4217 code, any case
     * @return the amount in minor units (cents / rappen / bani)
     * @throws IllegalArgumentException if the currency is not two-decimal, or the amount
     *                                  is null, negative, or has sub-cent precision
     * @throws ArithmeticException      if the amount does not fit in a long
     */
    public static long toMinorUnits(BigDecimal amount, String currency) {
        if (amount == null) {
            throw new IllegalArgumentException("Amount must not be null");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Currency must not be blank");
        }
        String iso = currency.trim().toUpperCase();
        if (ZERO_DECIMAL.contains(iso)) {
            throw new IllegalArgumentException(
                    "Zero-decimal currency not supported: " + iso + " (would overcharge 100x)");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Negative amount not supported: " + amount + " " + iso);
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Sub-cent amount not supported: " + amount + " " + iso);
        }
        // longValueExact: an amount too large for a long throws rather than wrapping.
        return amount.movePointRight(2).longValueExact();
    }
}
