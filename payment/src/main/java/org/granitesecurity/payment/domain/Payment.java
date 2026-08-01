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

@Table("payment")
@Getter
@Setter
public class Payment implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column("order_id")
    private Long orderId;

    private BigDecimal amount;

    private String currency;

    private String provider;

    @Column("provider_payment_id")
    private String providerPaymentId;

    /**
     * Whatever the frontend needs to complete this payment, as JSON. A CLIENT_SDK
     * provider stores a client secret in here; a REDIRECT provider stores a URL.
     * Nullable, and its shape must not be assumed.
     */
    @Column("provider_payload")
    private String providerPayload;

    /** The attempt currently in play. Null only for rows predating payment_attempt. */
    @Column("current_attempt_id")
    private UUID currentAttemptId;

    private String status;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Transient
    private boolean isNew = true;

    public Payment() {}

    public Payment(Long orderId, BigDecimal amount, String currency, String provider) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.amount = amount;
        this.currency = currency;
        this.provider = provider;
        this.status = PaymentStatus.CREATED.name();
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public void markNotNew() {
        this.isNew = false;
    }
}
