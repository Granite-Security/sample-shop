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

    /** ORDER or TOPUP. A top-up funds a balance and has no order (finance.md §6.1). */
    private String purpose = PURPOSE_ORDER;

    /** Who is topping up. Null for order payments — the order already knows. */
    private String username;

    /**
     * The storefront this payment was opened from, e.g. {@code https://sichocolate.com}
     * (docs/bugs/redirects.md §4.1). Null for rows predating the column, and for anything
     * opened without a resolvable origin — both fall back to the configured one.
     *
     * <p>Stored because the provider's return request cannot tell us: by then the shopper
     * is arriving from PayPal, not from the shop.
     */
    @Column("storefront_origin")
    private String storefrontOrigin;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Transient
    private boolean isNew = true;

    public static final String PURPOSE_ORDER = "ORDER";
    public static final String PURPOSE_TOPUP = "TOPUP";

    public Payment() {}

    /** A payment that funds {@code username}'s balance rather than an order. */
    public static Payment topup(String username, BigDecimal amount, String currency, String provider) {
        Payment payment = new Payment(null, amount, currency, provider);
        payment.setPurpose(PURPOSE_TOPUP);
        payment.setUsername(username);
        return payment;
    }

    public boolean isTopup() {
        return PURPOSE_TOPUP.equals(purpose);
    }

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
