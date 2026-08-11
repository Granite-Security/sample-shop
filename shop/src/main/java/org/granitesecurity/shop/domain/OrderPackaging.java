package org.granitesecurity.shop.domain;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What one order was packed in: one row per packaging group present in it (D42).
 *
 * <p>{@code unitPrice} and {@code unitCost} are frozen copies taken at placement,
 * for the same reason {@code OrderItem} freezes the product price (D26) — repricing
 * a box must not reach back and change what an order that already shipped charged
 * or cost.
 *
 * <p>Which item went in which box is not recorded. Nothing downstream asks, and an
 * assignment we never verify against what was actually packed would be a fiction.
 */
@Data
@Table("order_packaging")
public class OrderPackaging {
    @Id
    private Long id;
    @Column("order_id")
    private Long orderId;
    @Column("packaging_group_id")
    private Long packagingGroupId;
    @Column("packaging_option_id")
    private Long packagingOptionId;

    /** How many boxes of this option, i.e. {@code ceil(units / capacity)}. */
    private Integer quantity;

    @Column("unit_price")
    private BigDecimal unitPrice;
    @Column("unit_cost")
    private BigDecimal unitCost;
    @Column("created_at")
    private Instant createdAt;

    public OrderPackaging() {}

    public OrderPackaging(Long orderId, Long packagingGroupId, Long packagingOptionId,
                          Integer quantity, BigDecimal unitPrice, BigDecimal unitCost) {
        this.orderId = orderId;
        this.packagingGroupId = packagingGroupId;
        this.packagingOptionId = packagingOptionId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.unitCost = unitCost;
        this.createdAt = Instant.now();
    }
}
