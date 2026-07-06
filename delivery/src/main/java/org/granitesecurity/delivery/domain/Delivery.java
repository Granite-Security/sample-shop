package org.granitesecurity.delivery.domain;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class Delivery implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column("order_id")
    private Long orderId;

    private String status;

    @Column("payment_status")
    private String paymentStatus;

    private String items;

    @Column("recipient_name")
    private String recipientName;

    @Column("address_line1")
    private String addressLine1;

    @Column("address_line2")
    private String addressLine2;

    private String city;

    private String state;

    @Column("zip_code")
    private String zipCode;

    private String country;

    @Column("estimated_delivery_date")
    private Instant estimatedDeliveryDate;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Transient
    private boolean isNew = true;

    public Delivery() {}

    public Delivery(Long orderId, String recipientName, String addressLine1, String addressLine2,
                    String city, String state, String zipCode, String country, String items) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.status = DeliveryStatus.PENDING.name();
        this.paymentStatus = "UNPAID";
        this.items = items;
        this.recipientName = recipientName;
        this.addressLine1 = addressLine1;
        this.addressLine2 = addressLine2;
        this.city = city;
        this.state = state;
        this.zipCode = zipCode;
        this.country = country;
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
