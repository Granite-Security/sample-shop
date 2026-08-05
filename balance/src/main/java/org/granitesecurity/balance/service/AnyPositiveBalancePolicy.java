package org.granitesecurity.balance.service;

import org.springframework.stereotype.Component;

/**
 * v1 lending: <b>any positive balance buys anything.</b>
 *
 * <p>A user holding CHF 0.10 can buy a CHF 200 order and land at −199.90. That is
 * deliberate — the first cut of credit — and it means exposure per user is unbounded
 * until a real limit lands. Acceptable for demo credit; it would not be for real
 * money (docs/finance/finance.md §4.2).
 *
 * <p>Replacing this class is the entire change needed to introduce credit limits,
 * scoring or per-user terms. Nothing else knows the rule.
 */
@Component
public class AnyPositiveBalancePolicy implements CreditPolicy {

    @Override
    public long minimumBalanceBefore(long amountMinor) {
        // Strictly positive: 1 rappen is enough, 0 is not. Independent of the amount,
        // which is exactly what "unbounded credit" means.
        return 1L;
    }
}
