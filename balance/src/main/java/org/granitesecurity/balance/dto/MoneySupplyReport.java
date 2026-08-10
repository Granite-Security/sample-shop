package org.granitesecurity.balance.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * How much money we conjured, and whether it was spent (docs/finance/accounting.md §1,
 * questions 2 and 3).
 *
 * <p>No currency parameter: balance is CHF-only (finance.md D4). All amounts are rappen.
 */
public record MoneySupplyReport(
        String granularity,
        LocalDate from,
        LocalDate to,
        List<Bucket> buckets,
        Totals totals
) {

    /**
     * @param spentFromBackedMinor the remainder — spend less gift less credit. Not stored
     *                             anywhere, so it cannot disagree with the other two
     */
    public record Bucket(
            LocalDate bucket,
            String label,
            long giftedMinor,
            long toppedUpMinor,
            long spentMinor,
            long refundedMinor,
            long spentFromGiftMinor,
            long spentFromBackedMinor,
            long spentFromCreditMinor
    ) {}

    /**
     * @param giftedOutstandingMinor conjured money still sitting in user balances: free money
     *                               nobody has spent yet. This is the disclosed figure that
     *                               stands in for the liability policy (b) does not book —
     *                               the honest cost of not booking one (§2.4)
     */
    public record Totals(
            long giftedMinor,
            long toppedUpMinor,
            long spentMinor,
            long refundedMinor,
            long spentFromGiftMinor,
            long spentFromBackedMinor,
            long spentFromCreditMinor,
            long giftedOutstandingMinor
    ) {}
}
