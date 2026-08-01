package org.granitesecurity.payment.domain;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Table("refund")
@Getter
@Setter
public class Refund implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column("order_id")
    private Long orderId;

    @Column("payment_id")
    private UUID paymentId;

    @Column("provider_refund_id")
    private String providerRefundId;

    private BigDecimal amount;

    private String status;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Transient
    private boolean isNew = true;

    public Refund() {}

    public Refund(Long orderId, UUID paymentId, BigDecimal amount) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.paymentId = paymentId;
        this.amount = amount;
        this.status = RefundStatus.PENDING.name();
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public void markNotNew() {
        this.isNew = false;
    }
}
