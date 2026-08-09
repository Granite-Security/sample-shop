package org.granitesecurity.shop.domain;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Table("customer_order")
public class CustomerOrder {
    @Id
    private Long id;
    private String username;
    private String status;
    @Column("delivery_status")
    private String deliveryStatus;
    private BigDecimal total;
    /**
     * What {@code total} is denominated in, written once at placement.
     *
     * <p>Recorded per order so that changing the shop currency later cannot
     * reinterpret history — an order priced at 50.00 USD must keep reading as USD
     * after the shop moves to CHF.
     */
    private String currency;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;
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

    /**
     * The storefront this order was placed from, e.g. {@code https://sichocolate.com}
     * (docs/bugs/redirects.md §4.1). Published on OrderPlaced so payment can send the
     * shopper back to the right domain, and kept here because the event is long gone by
     * the time a retry or refund needs it.
     *
     * <p>Untrusted: it comes from a request header. payment allow-lists it before it
     * reaches a redirect. Null when the caller sent nothing to derive it from.
     */
    @Column("storefront_origin")
    private String storefrontOrigin;

    public CustomerOrder() {}

    public CustomerOrder(String username, String status, BigDecimal total, String currency,
                         String recipientName, String addressLine1, String addressLine2,
                         String city, String state, String zipCode, String country) {
        this.username = username;
        this.status = status;
        this.total = total;
        this.currency = currency;
        this.recipientName = recipientName;
        this.addressLine1 = addressLine1;
        this.addressLine2 = addressLine2;
        this.city = city;
        this.state = state;
        this.zipCode = zipCode;
        this.country = country;
        // created_at/updated_at are NOT NULL; R2DBC includes them in the INSERT,
        // bypassing the column defaults (same pattern as OutboxEvent).
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }
}
