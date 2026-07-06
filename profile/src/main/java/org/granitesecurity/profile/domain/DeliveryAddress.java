package org.granitesecurity.profile.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("delivery_address")
@Getter
@Setter
public class DeliveryAddress {

    @Id
    private Long id;

    private String username;

    private String label;

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

    @Column("is_default")
    private Boolean isDefault;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    public DeliveryAddress() {}
}
