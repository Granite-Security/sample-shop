package org.granitesecurity.payment.provider;

import org.granitesecurity.payment.service.MinorUnits;

import java.math.BigDecimal;

/**
 * An amount and the currency it is denominated in, kept together so a conversion
 * can never be done against the wrong currency.
 *
 * <p>Currency is normalised to upper case, matching how {@code payment.currency} is
 * persisted; providers that want lower case do that at their own boundary.
 */
public record Money(BigDecimal amount, String currency) {

    public Money {
        if (amount == null) {
            throw new IllegalArgumentException("Amount must not be null");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Currency must not be blank");
        }
        currency = currency.trim().toUpperCase();
    }

    /** The integer minor units providers charge in. Rejects sub-cent amounts. */
    public long minorUnits() {
        return MinorUnits.toMinorUnits(amount, currency);
    }

    /** ISO code in the lower case most provider APIs expect. */
    public String currencyLowerCase() {
        return currency.toLowerCase();
    }
}
