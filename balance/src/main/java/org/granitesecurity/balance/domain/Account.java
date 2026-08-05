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

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    public boolean isHouse() {
        return KIND_HOUSE.equals(kind);
    }
}
