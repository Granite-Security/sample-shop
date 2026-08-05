package org.granitesecurity.balance.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * v1 lending policy: any positive balance buys anything, so the required minimum
 * is 1 rappen regardless of the amount. If this test starts failing because the
 * policy grew a limit, that is the point — the rule lives in one place.
 */
class AnyPositiveBalancePolicyTest {

    private final CreditPolicy policy = new AnyPositiveBalancePolicy();

    @Test
    void requiresOnlyAStrictlyPositiveBalance() {
        assertEquals(1L, policy.minimumBalanceBefore(1L));
        assertEquals(1L, policy.minimumBalanceBefore(20_000L));
    }

    @Test
    void doesNotScaleWithTheAmount() {
        // CHF 0.01 must be enough to buy a CHF 10,000 order — unbounded credit,
        // deliberately (docs/finance/finance.md §4.2).
        assertEquals(policy.minimumBalanceBefore(1L), policy.minimumBalanceBefore(1_000_000L));
    }
}
