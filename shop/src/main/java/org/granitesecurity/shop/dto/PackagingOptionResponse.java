package org.granitesecurity.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Packaging option as stored")
public record PackagingOptionResponse(
        @Schema(description = "Packaging option ID", example = "2") Long id,
        @Schema(description = "Stable code", example = "PREMIUM") String code,
        @Schema(description = "Display name", example = "Premium gift box") String name,
        @Schema(description = "Description") String description,
        @Schema(description = "Price per box", example = "6.00") BigDecimal price,
        @Schema(description = "What one box costs us", example = "2.20") BigDecimal unitCost,
        @Schema(description = "Image of the box") String imageUrl,
        @Schema(description = "Still offered", example = "true") boolean active,
        @Schema(description = "Display order", example = "1") Integer sortOrder
) {
}
