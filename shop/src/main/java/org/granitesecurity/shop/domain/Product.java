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

    /**
     * What this product costs us, per unit (docs/finance/accounting.md §14.1).
     *
     * <p>A stated assumption, not a measurement: it defaults to half the price when a
     * product is created without one. Weighted average costing — one number, no lot
     * tracking and no FIFO layers.
     *
     * <p>Frozen onto OrderPlaced at order time (D26), because the cost of a sale is what
     * the goods cost when they were committed, not what the catalogue says months later.
     */
    @Column("unit_cost")
    private BigDecimal unitCost;

    private Integer stock;
    @Column("image_url")
    private String imageUrl;
    private String media;
    @Column("category_id")
    private Long categoryId;
    /** Soft-deleted: hidden from listings, still resolvable by id for order history. */
    private Boolean discontinued;

    /**
     * Which packaging group this product belongs to, or null when it needs no
     * packaging (docs/packaging/packaging.md D36).
     *
     * <p>Null is the answer for anything that already arrives in a box — a gift box
     * is its own box. Non-null says both "this needs packaging" and "here is what it
     * can share one with", which is one column rather than two that can disagree.
     */
    @Column("packaging_group_id")
    private Long packagingGroupId;
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
