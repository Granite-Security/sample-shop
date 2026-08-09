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
 * @param giftedOutstandingMinor conjured money still sitting in user balances — the
 *                              figure that stands in for the liability the accounting
 *                              policy does not book (accounting.md §2.4)
 * @param spentFromGiftMinor    of everything spent on orders, the conjured part
 * @param spentFromBackedMinor  the part funded by real top-ups; the remainder, never
 *                              stored, so it cannot disagree with the other two
 * @param spentFromCreditMinor  the part we lent rather than held
 * @param giftPoolDriftMinor    pools held, less what the ledger says entered them.
 *                              Must be 0: every conjured franc is either still in
 *                              someone's balance or has been spent (§12.1)
 * @param fundingSplitViolations entries whose split exceeds the movement it splits.
 *                              Must be 0
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
        long giftedOutstandingMinor,
        long spentFromGiftMinor,
        long spentFromBackedMinor,
        long spentFromCreditMinor,
        long giftPoolDriftMinor,
        long fundingSplitViolations,
        List<AccountDrift> drift
) {}
