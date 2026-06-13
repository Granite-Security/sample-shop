package org.granitesecurity.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request to create or update a category")
public record CreateCategoryRequest(
        @Schema(description = "Category name", example = "Electronics") String name,
        @Schema(description = "Category description", example = "Electronic gadgets and accessories") String description
) {
}
