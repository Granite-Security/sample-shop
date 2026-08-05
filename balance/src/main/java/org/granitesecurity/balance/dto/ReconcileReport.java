package org.granitesecurity.balance.dto;

import java.util.List;

/**
 * The central bank's own books (docs/finance/finance.md §7.1).
 *
 * <p>{@code balanced} is the only field that must ever be true; the rest are the
 * numbers an operator wants. All amounts are rappen.
 *
 * @param ledgerSumMinor        must be 0 — money neither created nor destroyed
 * @param userTotalMinor        circulating credit held by people
 * @param houseTotalMinor       the negative of the above, if the books balance
 * @param unbackedIssuedMinor   |house:gift| — credit conjured from nothing
 * @param backedIssuedMinor     |house:topup| — credit backed by real payments
 * @param redeemedMinor         |house:shop| — credit spent on orders
 * @param creditOutstandingMinor sum of negative user balances: money lent out
 * @param drift                 accounts whose cache disagrees with their entries
 */
public record ReconcileReport(
        boolean balanced,
        long ledgerSumMinor,
        long userTotalMinor,
        long houseTotalMinor,
        long unbackedIssuedMinor,
        long backedIssuedMinor,
        long redeemedMinor,
        long creditOutstandingMinor,
        List<AccountDrift> drift
) {}
