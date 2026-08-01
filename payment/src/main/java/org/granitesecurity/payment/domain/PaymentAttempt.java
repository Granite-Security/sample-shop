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

/**
 * One try at taking the money for an order.
 *
 * <p>A {@link Payment} holds the order's current state; the attempts hold how it got
 * there. Without this, retrying overwrites {@code provider_payment_id} in place: no
 * audit trail, and {@code /sync} can no longer reconcile the abandoned intent.
 *
 * <p>It is also what makes "refundable for the amount actually paid" answerable — the
 * succeeded attempt records what was captured, at which provider. A partial DB unique
 * index enforces at most one succeeded attempt per order, so a double charge fails
 * loudly at the database rather than quietly reconciling.
 */
@Table("payment_attempt")
@Getter
@Setter
public class PaymentAttempt implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column("payment_id")
    private UUID paymentId;

    @Column("order_id")
    private Long orderId;

    private String provider;

    @Column("provider_payment_id")
    private String providerPaymentId;

    @Column("provider_payload")
    private String providerPayload;

    private BigDecimal amount;

    private String currency;

    private String status;

    /** Why the provider declined, when it said. Shown to the shopper, so kept as data. */
    @Column("decline_reason")
    private String declineReason;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Transient
    private boolean isNew = true;

    public PaymentAttempt() {}

    public PaymentAttempt(UUID paymentId, Long orderId, String provider, BigDecimal amount, String currency) {
        this.id = UUID.randomUUID();
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.provider = provider;
        this.amount = amount;
        this.currency = currency;
        this.status = PaymentStatus.CREATED.name();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public void markNotNew() {
        this.isNew = false;
    }
}
