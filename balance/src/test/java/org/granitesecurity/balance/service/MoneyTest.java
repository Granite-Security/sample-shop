package org.granitesecurity.balance.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pure conversion. No Spring context, no database. */
class MoneyTest {

    @Test
    void convertsChfToRappen() {
        assertEquals(1000L, Money.toRappen(new BigDecimal("10")));
        assertEquals(1000L, Money.toRappen(new BigDecimal("10.00")));
        assertEquals(1L, Money.toRappen(new BigDecimal("0.01")));
        assertEquals(2455L, Money.toRappen(new BigDecimal("24.55")));
    }

    @Test
    void convertsRappenBackToChf() {
        assertEquals(new BigDecimal("10.00"), Money.toChf(1000L));
        assertEquals(new BigDecimal("0.01"), Money.toChf(1L));
        // Credit extended: balances go negative and must still render.
        assertEquals(new BigDecimal("-190.00"), Money.toChf(-19000L));
    }

    @Test
    void roundTripsWithoutLoss() {
        // Zero is excluded on purpose: toRappen rejects it, because no movement of
        // zero money is ever a legitimate request.
        for (long rappen : new long[]{1, 99, 100, 2455, 19000, 123456789}) {
            assertEquals(rappen, Money.toRappen(Money.toChf(rappen)),
                    "round trip failed for " + rappen);
        }
    }

    @Test
    void rejectsAmountsThatAreNotMoney() {
        assertThrows(IllegalArgumentException.class, () -> Money.toRappen(null));
        assertThrows(IllegalArgumentException.class, () -> Money.toRappen(BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> Money.toRappen(new BigDecimal("-5")));
    }

    @Test
    void rejectsSubRappenRatherThanRounding() {
        // Silently rounding here is how a ledger starts disagreeing with itself.
        assertThrows(IllegalArgumentException.class, () -> Money.toRappen(new BigDecimal("0.005")));
        assertThrows(IllegalArgumentException.class, () -> Money.toRappen(new BigDecimal("10.999")));
    }

    @Test
    void rejectsAmountsTooLargeForALong() {
        assertThrows(ArithmeticException.class,
                () -> Money.toRappen(new BigDecimal("999999999999999999999")));
    }
}
