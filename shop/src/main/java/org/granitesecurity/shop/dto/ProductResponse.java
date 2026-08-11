package org.granitesecurity.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

@Schema(description = "Product returned in catalog responses")
public record ProductResponse(
        @Schema(description = "Product ID", example = "1") Long id,
        @Schema(description = "Product name", example = "Wireless Headphones") String name,
        @Schema(description = "Product description", example = "Bluetooth 5.2 noise-canceling headphones") String description,
        @Schema(description = "Price", example = "79.99") BigDecimal price,
        @Schema(description = "Available stock", example = "50") Integer stock,
        @Schema(description = "Category ID this product belongs to", example = "1") Long categoryId,
        @Schema(description = "Product image URL", example = "https://picsum.photos/seed/headphones/400/400") String imageUrl,
        @Schema(description = "Gallery/video media attached to the product") List<MediaItem> media,
        @Schema(description = "Retired from the catalog; hidden from listings but kept for order history", example = "false") boolean discontinued,
        @Schema(description = "Packaging group, or null when the product needs no packaging "
                + "(docs/packaging/packaging.md D36)", example = "1") Long packagingGroupId
) {

    /** The shape before packaging existed; reads as a product that needs no box. */
    public ProductResponse(Long id, String name, String description, BigDecimal price, Integer stock,
                           Long categoryId, String imageUrl, List<MediaItem> media, boolean discontinued) {
        this(id, name, description, price, stock, categoryId, imageUrl, media, discontinued, null);
    }
}
