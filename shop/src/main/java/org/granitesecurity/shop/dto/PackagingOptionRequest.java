package org.granitesecurity.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Request to create or update a packaging option")
public record PackagingOptionRequest(
        @Schema(description = "Stable code, uppercase", example = "PREMIUM") String code,
        @Schema(description = "Display name", example = "Premium gift box") String name,
        @Schema(description = "Description shown on the choice card") String description,
        @Schema(description = "Price per box; 0 is a price, not a missing value", example = "6.00") BigDecimal price,
        // Never derived from price. A free box still costs us something, and that cost
        // is the only reason accounting can expense it (D44).
        @Schema(description = "What one box costs us", example = "2.20") BigDecimal unitCost,
        @Schema(description = "Image of the box") String imageUrl,
        // Null leaves it as it is on update, so repricing a box does not resurrect a
        // retired one — same rule as discontinued on products.
        @Schema(description = "Set false to retire; null leaves it unchanged", example = "true") Boolean active,
        @Schema(description = "Display order; the lowest active one is pre-selected", example = "1") Integer sortOrder
) {
}
