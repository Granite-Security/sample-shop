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

    /**
     * How much of {@code total} is boxes (docs/packaging/packaging.md D42).
     *
     * <p>Split out rather than folded into {@code total} silently: without it, a
     * reconciliation of the line items against the order total stops balancing the
     * moment a shopper picks a premium box, and nothing says why.
     *
     * <p>Zero for an order that needed no packaging, and also for one packed entirely
     * in free boxes — free is a price, so the charge really is zero even though a box
     * was used and cost us something. What it cost lives on {@code order_packaging}.
     */
    @Column("packaging_total")
    private BigDecimal packagingTotal;

    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    /**
     * The three moments a sale is bucketed by in the revenue reports
     * (docs/finance/accounting.md D4), each written <em>once</em>, on first entry
     * into the status that means it — see {@code OrderService.updateOrderStatus}.
     *
     * <p>They exist because neither of the timestamps above can bucket money.
     * {@code createdAt} is when the order was submitted, which is not when it was
     * paid; {@code updatedAt} moves on every transition, and the status graph walks
     * backwards ({@code REIMBURSED -> RETURNED}), so bucketing on it lets a retry
     * months later move a sale into a different month.
     *
     * <p>{@code deliveredAt} is the accrual recognition point: control passes to the
     * customer on delivery, not on payment (IFRS 15.31). {@code refundedAt} is set at
     * {@code REIMBURSED} only — {@code RETURNED} is a request, not money leaving.
     */
    @Column("paid_at")
    private Instant paidAt;
    @Column("delivered_at")
    private Instant deliveredAt;
    @Column("refunded_at")
    private Instant refundedAt;
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
        this.packagingTotal = BigDecimal.ZERO;
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
