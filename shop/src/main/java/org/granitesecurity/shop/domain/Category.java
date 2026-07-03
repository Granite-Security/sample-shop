package org.granitesecurity.shop.domain;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Data
@Table("category")
public class Category {
    @Id
    private Long id;
    private String name;
    private String description;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    public Category() {}

    public Category(String name, String description) {
        this.name = name;
        this.description = description;
    }
}
