package org.granitesecurity.balance.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One leg of one movement. Append-only: never updated, never deleted (D12).
 *
 * <p>The two legs of a movement share a {@code transferId} and their signed
 * {@code amountMinor} values sum to zero, which is what makes the "do the books
 * balance?" check a single query over the whole table.
 */
@Table("ledger_entry")
@Getter
@Setter
public class LedgerEntry {

    public static final String KIND_TOPUP = "TOPUP";
    public static final String KIND_SPEND = "SPEND";
    public static final String KIND_REFUND = "REFUND";
    public static final String KIND_TRANSFER = "TRANSFER";
    public static final String KIND_GIFT = "GIFT";

    @Id
    private Long id;

    @Column("transfer_id")
    private UUID transferId;

    @Column("account_id")
    private Long accountId;

    /** Signed: negative on the debited side, positive on the credited side. */
    @Column("amount_minor")
    private long amountMinor;

    private String currency = "CHF";

    private String kind;

    /** Order id, payment id or acting admin — whichever identifies this movement. */
    private String reference;

    private String memo;

    /**
     * The conjured portion of this leg (docs/finance/accounting.md §5).
     *
     * <p>Recorded in both directions: drawn out of the payer's pool on a debit, and
     * carried in on the credit leg of a GIFT, TRANSFER or REFUND. Both directions,
     * because the pool has to be provable from the ledger alone — a refund that puts
     * gifted money back would otherwise be indistinguishable, to any check, from
     * conjured money that had been spent twice (§12.1).
     */
    @Column("gift_funded_minor")
    private long giftFundedMinor;

    /**
     * The lent portion of this leg: money the payer did not have. Debits only.
     *
     * <p>Its own bucket rather than folded into gift or backed, because it is neither:
     * a user holding CHF 10 who buys CHF 200 of goods was never gifted and never topped
     * up CHF 190 of it. gift + credit + backed = amount is the invariant that says so.
     */
    @Column("credit_funded_minor")
    private long creditFundedMinor;

    @Column("created_at")
    private Instant createdAt;
}
