package org.granitesecurity.shop.domain;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Table("product")
public class Product {
    @Id
    private Long id;
    private String name;
    private String description;
    private BigDecimal price;
    private Integer stock;
    @Column("category_id")
    private Long categoryId;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    public Product() {}

    public Product(String name, BigDecimal price, Integer stock, Long categoryId) {
        this.name = name;
        this.price = price;
        this.stock = stock;
        this.categoryId = categoryId;
    }
}
