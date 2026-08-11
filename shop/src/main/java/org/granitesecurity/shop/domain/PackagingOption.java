package org.granitesecurity.shop.domain;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A box we offer — today {@code FREE} and {@code PREMIUM} (D38).
 *
 * <p>How many fit is deliberately not here: capacity belongs to the
 * {@code (group, option)} pair, because a box holds twelve truffles or four
 * rabbits and neither side knows that alone.
 */
@Data
@Table("packaging_option")
public class PackagingOption {
    @Id
    private Long id;
    private String code;
    private String name;
    private String description;

    /** What the shopper pays per box. Zero is a price, not a missing value. */
    private BigDecimal price;

    /**
     * What the box costs us, per box. Stored rather than derived from {@code price}
     * precisely because {@code FREE} charges 0.00 and still costs 0.40 — a box given
     * away is a fulfilment cost expensed at delivery (docs/finance/accounting.md D44),
     * and nothing else would record it.
     */
    @Column("unit_cost")
    private BigDecimal unitCost;

    @Column("image_url")
    private String imageUrl;

    /**
     * Retired options stay in the table. {@code order_packaging} points here, so an
     * order must keep resolving the box it actually shipped in long after we stop
     * offering it — the same reason products are discontinued rather than deleted.
     */
    private Boolean active;

    /** Display order, and the tie-break the server uses to pick the default. */
    @Column("sort_order")
    private Integer sortOrder;

    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    public PackagingOption() {}
}
