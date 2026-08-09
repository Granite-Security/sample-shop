package org.granitesecurity.balance.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * One account in the ledger (docs/finance/finance.md §2).
 *
 * <p>{@code balanceMinor} is a <em>cache</em> of the sum of this account's entries,
 * not the source of truth — D1. It is written in the same transaction as the entries
 * and checked against them by the reconcile endpoint.
 */
@Table("account")
@Getter
@Setter
public class Account {

    /** House accounts are the counterparty to money entering and leaving the system. */
    public static final String KIND_USER = "USER";
    public static final String KIND_HOUSE = "HOUSE";

    public static final String HOUSE_TOPUP = "house:topup";
    public static final String HOUSE_GIFT = "house:gift";
    public static final String HOUSE_SHOP = "house:shop";
    public static final String HOUSE_REFUND = "house:refund";

    @Id
    private Long id;

    /** The JWT subject. Unique: one identity, one account (§7.2). */
    private String username;

    private String kind = KIND_USER;

    @Column("balance_minor")
    private long balanceMinor;

    private String currency = "CHF";

    /**
     * How much of this balance is conjured money (docs/finance/accounting.md §5).
     *
     * <p>Grows on a GIFT and on the receiving side of a transfer or refund that carried
     * gifted francs; every debit draws it down <em>first</em>. Gift-first is the
     * conservative ordering — it maximises contra-revenue and minimises recognised
     * revenue — and it is the only one that needs no per-franc history.
     *
     * <p>Always zero on a house account: house:gift is where conjured money comes from,
     * not somewhere it is held.
     */
    @Column("gift_pool_minor")
    private long giftPoolMinor;

    /**
     * When this balance last went negative, or null if it is not.
     *
     * <p>A negative user balance is a trade receivable, and IFRS 9's provision matrix
     * buckets receivables by age (accounting.md §2.6) — an age nothing else records.
     * Cleared the moment the balance returns to zero or above, so it always means "has
     * been owing since", never "was owing once".
     */
    @Column("negative_since")
    private Instant negativeSince;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    public boolean isHouse() {
        return KIND_HOUSE.equals(kind);
    }
}
