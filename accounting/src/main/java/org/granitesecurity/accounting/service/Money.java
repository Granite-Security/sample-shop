package org.granitesecurity.accounting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Decimal amounts arrive on events; the books keep rappen. Conversion happens here and
 * nowhere else (D16).
 *
 * <p>Two methods rather than one that guesses, because the guess is unresolvable: a JSON
 * {@code 60} is CHF 60.00 on {@code payments.events} and 60 rappen on {@code balance.events},
 * and nothing in the value says which. The caller knows; a heuristic would be wrong by a
 * factor of a hundred and would look right in every test written against the other topic.
 */
public final class Money {

    /** For amounts expressed in major units — shop's {@code total}, payment's {@code amount}. */
    public static long fromDecimal(Object value) {
        if (value == null) {
            return 0L;
        }
        return new BigDecimal(value.toString())
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact();
    }

    /** For amounts already in rappen — everything on {@code balance.events}. */
    public static long fromMinor(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(value.toString().trim());
    }

    private Money() {}
}
