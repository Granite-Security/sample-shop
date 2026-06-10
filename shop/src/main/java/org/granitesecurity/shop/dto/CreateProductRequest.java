package org.granitesecurity.shop.dto;

import java.math.BigDecimal;

public record CreateProductRequest(
        String name,
        String description,
        BigDecimal price,
        Integer stock,
        Long categoryId,
        String imageUrl
) {
}
