package org.granitesecurity.accounting.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * The four forms of §15.1. Three typed ones exist so the common cases are not hand-built
 * journals — a form cannot produce an unbalanced entry or a debit to the wrong side — and a
 * raw escape hatch for everything else. All four render down to journals through the same
 * machinery as the event consumers, so there is still exactly one place that knows what an
 * expense looks like.
 *
 * <p>Every one carries an idempotency key, the same convention as balance (finance.md D5).
 * The acting user comes from the JWT and is never in the body.
 */
public final class ManualJournalRequests {

    /**
     * Buying stock or equipment.
     *
     * @param onCredit true when a supplier will invoice us — credits 2500 rather than the
     *                 bank. Buying on credit and paying cash are different facts, not a
     *                 presentation choice
     */
    @Schema(description = "A purchase of stock, equipment or a service")
    public record Purchase(
            @Schema(example = "1200", description = "What is being acquired: 1200 inventory, 1500 equipment, or an expense account")
            String accountCode,
            @Schema(example = "45000", description = "Rappen") long amountMinor,
            @Schema(example = "false") boolean onCredit,
            Instant occurredAt,
            String memo,
            String idempotencyKey) {}

    /**
     * An operating cost.
     *
     * @param incurredBy the person who paid for it personally, if anyone. Set, and the
     *                   credit goes to 2600 as a payable to them rather than to the bank
     *                   (D35) — we owe a person, and the books should say so
     */
    @Schema(description = "An operating expense")
    public record Expense(
            @Schema(example = "6900", description = "Defaults to 6900 other operating expenses")
            String accountCode,
            @Schema(example = "12000", description = "Rappen") long amountMinor,
            @Schema(example = "manager") String incurredBy,
            Instant occurredAt,
            String memo,
            String idempotencyKey) {}

    /** Paying a member of staff back what they spent. */
    @Schema(description = "Settling a payable to a person")
    public record Reimbursement(
            @Schema(example = "manager") String party,
            @Schema(example = "12000", description = "Rappen") long amountMinor,
            Instant occurredAt,
            String memo,
            String idempotencyKey) {}

    /** The escape hatch: a raw balanced journal for anything the three forms do not cover. */
    @Schema(description = "A raw journal — debits must equal credits")
    public record RawJournal(
            Instant occurredAt,
            String memo,
            List<Line> lines,
            String idempotencyKey) {

        @Schema(description = "One line; exactly one side must be non-zero")
        public record Line(String accountCode, long debitMinor, long creditMinor,
                           @Schema(description = "Who we owe, on a 2600 line") String party,
                           String memo) {}
    }
}
