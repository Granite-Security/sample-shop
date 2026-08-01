package org.granitesecurity.payment.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinorUnitsTest {

    @Test
    void convertsTwoDecimalAmounts() {
        assertThat(MinorUnits.toMinorUnits(new BigDecimal("10.99"), "CHF")).isEqualTo(1099L);
        assertThat(MinorUnits.toMinorUnits(new BigDecimal("10"), "CHF")).isEqualTo(1000L);
        assertThat(MinorUnits.toMinorUnits(new BigDecimal("0.50"), "chf")).isEqualTo(50L);
        assertThat(MinorUnits.toMinorUnits(BigDecimal.ZERO, "CHF")).isZero();
    }

    @Test
    void trailingZerosAreNotSubCentPrecision() {
        // 10.9900 has scale 4 but is exactly representable — must not be rejected.
        assertThat(MinorUnits.toMinorUnits(new BigDecimal("10.9900"), "CHF")).isEqualTo(1099L);
    }

    @Test
    void rejectsSubCentInsteadOfTruncating() {
        // The old x100 + longValue() silently produced 1099 here, undercharging.
        assertThatThrownBy(() -> MinorUnits.toMinorUnits(new BigDecimal("10.999"), "CHF"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Sub-cent");
    }

    @Test
    void rejectsZeroDecimalCurrency() {
        assertThatThrownBy(() -> MinorUnits.toMinorUnits(new BigDecimal("1000"), "JPY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Zero-decimal");
    }

    @Test
    void rejectsNegativeAndBlankInput() {
        assertThatThrownBy(() -> MinorUnits.toMinorUnits(new BigDecimal("-1.00"), "CHF"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MinorUnits.toMinorUnits(new BigDecimal("1.00"), " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOverflowInsteadOfWrapping() {
        assertThatThrownBy(() -> MinorUnits.toMinorUnits(new BigDecimal("1E+30"), "CHF"))
                .isInstanceOf(ArithmeticException.class);
    }
}
