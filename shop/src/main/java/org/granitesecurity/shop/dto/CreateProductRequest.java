package org.granitesecurity.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

@Schema(description = "Request to create or update a product")
public record CreateProductRequest(
        @Schema(description = "Product name", example = "Wireless Headphones") String name,
        @Schema(description = "Product description", example = "Bluetooth 5.2 noise-canceling headphones") String description,
        @Schema(description = "Price", example = "79.99") BigDecimal price,
        @Schema(description = "Initial stock", example = "50") Integer stock,
        @Schema(description = "Category ID", example = "1") Long categoryId,
        @Schema(description = "Product image URL", example = "https://picsum.photos/seed/headphones/400/400") String imageUrl,
        @Schema(description = "Gallery/video media attached to the product") List<MediaItem> media
) {
}
