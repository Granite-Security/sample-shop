package org.granitesecurity.shop.domain;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Table("order_item")
public class OrderItem {
    @Id
    private Long id;
    @Column("order_id")
    private Long orderId;
    @Column("product_id")
    private Long productId;
    private Integer quantity;
    @Column("unit_price")
    private BigDecimal unitPrice;
    @Column("created_at")
    private Instant createdAt;

    public OrderItem() {}

    public OrderItem(Long orderId, Long productId, Integer quantity, BigDecimal unitPrice) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.createdAt = Instant.now();
    }
}
