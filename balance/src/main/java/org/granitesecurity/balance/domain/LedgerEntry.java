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

    @Column("created_at")
    private Instant createdAt;
}
