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
    private BigDecimal total;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    public CustomerOrder() {}

    public CustomerOrder(String username, String status, BigDecimal total) {
        this.username = username;
        this.status = status;
        this.total = total;
    }
}
