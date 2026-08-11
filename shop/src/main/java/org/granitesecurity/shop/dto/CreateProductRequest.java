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
        @Schema(description = "RECEIPT | DAMAGE | SHRINKAGE | COUNT_CORRECTION", example = "DAMAGE") String stockReason,
        // Nullable for the same reason as discontinued: on update, null means "leave it
        // alone", so an admin editing a price does not silently make a boxed product
        // unpackageable. Clearing it needs the explicit sentinel below — see
        // CatalogService.
        @Schema(description = "Packaging group this product belongs to; null means it needs no "
                + "packaging on create, and leaves it unchanged on update. Send 0 to clear it.",
                example = "1") Long packagingGroupId
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
        this(name, description, price, stock, null, categoryId, imageUrl, media, discontinued, null, null);
    }

    /** The shape before {@code packagingGroupId} existed. */
    public CreateProductRequest(String name, String description, BigDecimal price, Integer stock,
                                BigDecimal unitCost, Long categoryId, String imageUrl,
                                List<MediaItem> media, Boolean discontinued, String stockReason) {
        this(name, description, price, stock, unitCost, categoryId, imageUrl, media, discontinued,
                stockReason, null);
    }

    /**
     * What {@code packagingGroupId} must be to mean "this product no longer needs
     * packaging".
     *
     * <p>Null already means "not stated" on update, and both meanings are needed: an
     * admin fixing a typo sends no packaging group and expects the box to stay, while
     * one who has started shipping a product pre-boxed needs a way to say so. A sentinel
     * rather than a second boolean field, because two fields can contradict each other.
     */
    public static final long CLEAR_PACKAGING_GROUP = 0L;
}
