package org.granitesecurity.accounting.service;

import java.util.List;

/**
 * What a fact turns into. Three outcomes, and the difference between the last two is the
 * whole reason facts are stored before journals are derived (docs/finance/accounting.md §6).
 */
public sealed interface PostingOutcome {

    /** Book it. */
    record Post(List<PostingLine> lines, String memo) implements PostingOutcome {}

    /**
     * Correctly produces no journal, and never will: a gift issuance, a failed payment,
     * an order priced in a currency these books do not keep. The reason is recorded, so
     * "why is there no entry for this?" has an answer.
     */
    record Ignore(String reason) implements PostingOutcome {}

    /**
     * Cannot be booked <em>yet</em> — a prerequisite has not arrived. The fact stays
     * UNPOSTED and is retried. Never dropped: a delivery whose order is still in flight
     * is money that will need booking, not noise.
     */
    record Wait(String waitingFor) implements PostingOutcome {}

    /** One side of one line. Debit and credit have separate factories so neither can be signed wrong. */
    record PostingLine(String accountCode, long debitMinor, long creditMinor, String memo) {

        public static PostingLine debit(String accountCode, long amountMinor) {
            return new PostingLine(accountCode, amountMinor, 0, null);
        }

        public static PostingLine credit(String accountCode, long amountMinor) {
            return new PostingLine(accountCode, 0, amountMinor, null);
        }
    }
}
