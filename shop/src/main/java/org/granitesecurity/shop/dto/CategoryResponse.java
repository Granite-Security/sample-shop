package org.granitesecurity.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Product category")
public record CategoryResponse(
        @Schema(description = "Category ID", example = "1") Long id,
        @Schema(description = "Category name", example = "Electronics") String name,
        @Schema(description = "Category description", example = "Electronic gadgets and accessories") String description
) {
}
