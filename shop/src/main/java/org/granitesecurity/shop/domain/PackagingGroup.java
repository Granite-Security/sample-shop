package org.granitesecurity.shop.domain;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * A set of products that can share a box (docs/packaging/packaging.md D36, D37).
 *
 * <p>Also answers "does this need packaging at all": a product with no group needs
 * none, because it already arrived in something. The two questions are one column
 * on purpose — a flag saying "needs a box" and a separate compatibility column can
 * disagree, and an order nobody can pack is the result.
 *
 * <p>Not {@code category}: category decides which storefront a product appears on
 * (see changelog 011), and what shares a box has nothing to do with what shares a
 * domain.
 */
@Data
@Table("packaging_group")
public class PackagingGroup {
    @Id
    private Long id;
    private String code;
    private String name;
    private String description;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    public PackagingGroup() {}

    public PackagingGroup(String code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description;
        // created_at/updated_at are NOT NULL; R2DBC includes them in the INSERT,
        // bypassing the column defaults (same pattern as CustomerOrder).
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }
}
