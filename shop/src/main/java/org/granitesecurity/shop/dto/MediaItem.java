package org.granitesecurity.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A single product media asset stored in the storage service")
public record MediaItem(
        @Schema(description = "Storage object key", example = "products/3f2c/hero.jpg") String key,
        @Schema(description = "Public URL for the object", example = "http://product-media.localhost:3902/products/3f2c/hero.jpg") String url,
        @Schema(description = "MIME type of the object", example = "image/jpeg") String contentType,
        @Schema(description = "Whether this is the product's default/thumbnail image", example = "false") boolean isDefault
) {
}
