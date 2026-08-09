package org.granitesecurity.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * The cash view of sales (docs/finance/accounting.md §10): what moved, and when.
 *
 * <p>This is <em>not</em> what we earned. Revenue is recognised on delivery
 * (IFRS 15.31), and that view is booked by the accounting service. This one buckets
 * money by the day it actually moved, which is the question an operator asks first
 * and the only view that reconciles against the ledger.
 *
 * <p>Amounts are minor units (rappen), matching balance, and converted at this edge
 * so nothing downstream does arithmetic on a decimal string (D16).
 */
@Schema(description = "Cash view of sales and refunds, bucketed by year, month or week")
public record RevenueReport(
        @Schema(description = "How time is cut", example = "month") RevenueGranularity granularity,
        @Schema(description = "The currency these orders were priced in", example = "CHF") String currency,
        @Schema(description = "First bucket start, inclusive") LocalDate from,
        @Schema(description = "End of the window, exclusive") LocalDate to,
        @Schema(description = "One row per bucket, including empty ones") List<RevenueBucket> buckets,
        @Schema(description = "Computed server-side over the same window") RevenueTotals totals
) {

    /**
     * A single bucket. An order contributes to a sales bucket and, if it is ever
     * refunded, to a refund bucket — independently, and usually not the same one.
     * A bucket can therefore be net-negative, which is correct and not a bug: the
     * refund lands where the money went back (§12.2).
     */
    @Schema(description = "One time bucket of the cash view")
    public record RevenueBucket(
            @Schema(description = "Bucket start date; weeks are ISO and start Monday") LocalDate bucket,
            @Schema(description = "Display label", example = "Jul 2026") String label,
            @Schema(description = "Orders that reached PAID in this bucket") long grossMinor,
            @Schema(description = "Refunds that reached REIMBURSED in this bucket") long refundedMinor,
            @Schema(description = "gross - refunded") long netMinor,
            @Schema(description = "Number of orders paid") long orderCount,
            @Schema(description = "Number of refunds settled") long refundCount,
            @Schema(description = "Refunds requested against this bucket's sales but not yet settled")
            long returnsPendingMinor
    ) {}

    /** Totals over the whole window. The browser must never re-sum the buckets to get these. */
    @Schema(description = "Window totals")
    public record RevenueTotals(
            long grossMinor,
            long refundedMinor,
            long netMinor,
            long orderCount,
            long refundCount,
            long returnsPendingMinor
    ) {}
}
