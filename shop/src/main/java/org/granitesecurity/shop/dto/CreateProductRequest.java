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
        // Null means "we have not stated one", and the 50%-of-price rule fills it in
        // (CatalogService.createProduct). On update, null leaves the existing cost alone
        // rather than silently re-deriving it from a new price.
        @Schema(description = "Cost per unit; defaults to half the price when absent", example = "39.99") BigDecimal unitCost,
        @Schema(description = "Category ID", example = "1") Long categoryId,
        @Schema(description = "Product image URL", example = "https://picsum.photos/seed/headphones/400/400") String imageUrl,
        @Schema(description = "Gallery/video media attached to the product") List<MediaItem> media,
        // Boxed and nullable on purpose: on update, null means "leave as it is",
        // so an admin editing a discontinued product's price does not silently
        // put it back on sale. On create, null is simply false.
        @Schema(description = "Set false to restore a discontinued product; null leaves it unchanged", example = "false") Boolean discontinued,
        // Why the stock number changed. Absent is not an error — the rule in
        // CatalogService infers RECEIPT for an increase and COUNT_CORRECTION for a
        // decrease — but stating it is what lets accounting book a shrinkage as
        // shrinkage rather than as a mystery.
        @Schema(description = "RECEIPT | DAMAGE | SHRINKAGE | COUNT_CORRECTION", example = "DAMAGE") String stockReason
) {

    /**
     * The shape before {@code unitCost} and {@code stockReason} existed.
     *
     * <p>Both new components are optional by design — a cost that is not stated is derived
     * from the price, and a reason that is not stated is inferred from the direction of the
     * change — so callers that never had an opinion about either keep working unchanged.
     * That includes the seed data and every JSON body already in the wild: a record's
     * canonical constructor is fixed-arity, but its deserialisation is by name.
     */
    public CreateProductRequest(String name, String description, BigDecimal price, Integer stock,
                                Long categoryId, String imageUrl, List<MediaItem> media,
                                Boolean discontinued) {
        this(name, description, price, stock, null, categoryId, imageUrl, media, discontinued, null);
    }
}
