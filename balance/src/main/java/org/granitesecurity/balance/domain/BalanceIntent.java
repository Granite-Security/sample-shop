package org.granitesecurity.balance.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * What payment holds an id for. Mirrors a PayPal order: it exists, it is approved,
 * and no money has moved until capture (docs/finance/finance.md §4.1).
 */
@Table("balance_intent")
@Getter
@Setter
public class BalanceIntent implements Persistable<UUID> {

    public static final String CREATED = "CREATED";
    public static final String CAPTURED = "CAPTURED";
    public static final String FAILED = "FAILED";
    public static final String REFUNDED = "REFUNDED";

    @Id
    private UUID id;

    private String username;

    @Column("amount_minor")
    private long amountMinor;

    @Column("order_id")
    private Long orderId;

    private String status;

    /** Null until capture: no ledger rows exist before then. */
    @Column("transfer_id")
    private UUID transferId;

    @Column("refund_id")
    private UUID refundId;

    @Column("decline_reason")
    private String declineReason;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    @Transient
    private boolean isNew;

    @Override
    public boolean isNew() {
        return isNew;
    }

    public void markNew() {
        this.isNew = true;
    }
}
