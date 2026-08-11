package org.granitesecurity.shop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * What boxing this cart would cost, per group and per option.
 *
 * <p>Every option compatible with each group is returned priced, so the UI renders
 * choices without doing arithmetic of its own — and so the number it shows is the
 * same number checkout will charge.
 */
@Schema(description = "Packaging options and prices for a cart")
public record PackagingQuoteResponse(
        @Schema(description = "False when nothing in the cart needs a box; groups is then empty",
                example = "true") boolean packagingRequired,
        @Schema(description = "Currency the prices are denominated in", example = "CHF") String currency,
        @Schema(description = "One entry per packaging group present in the cart") List<GroupQuote> groups
) {

    @Schema(description = "One packaging group present in the cart, with its options")
    public record GroupQuote(
            @Schema(description = "Packaging group ID", example = "1") Long groupId,
            @Schema(description = "Stable code", example = "TRUFFLE") String code,
            @Schema(description = "Display name", example = "Truffles") String name,
            @Schema(description = "Description shown next to the choice") String description,
            @Schema(description = "Total units in the cart belonging to this group", example = "13") int units,
            @Schema(description = "Compatible active options, cheapest-first by sort order")
            List<OptionQuote> options
    ) {}

    @Schema(description = "One box option, priced for this cart")
    public record OptionQuote(
            @Schema(description = "Packaging option ID", example = "2") Long optionId,
            @Schema(description = "Stable code", example = "PREMIUM") String code,
            @Schema(description = "Display name", example = "Premium gift box") String name,
            @Schema(description = "Description shown on the choice card") String description,
            @Schema(description = "Image of the box, if we have one") String imageUrl,
            @Schema(description = "How many units of this group fit in one box", example = "12") int capacity,
            @Schema(description = "Boxes needed: ceil(units / capacity)", example = "2") int packages,
            @Schema(description = "Price per box", example = "6.00") BigDecimal unitPrice,
            @Schema(description = "packages x unitPrice", example = "12.00") BigDecimal total,
            // "default" is a Java keyword, so the component cannot be named for the
            // field it serialises to.
            @JsonProperty("default")
            @Schema(description = "Pre-selected when the shopper expresses no preference; "
                    + "the lowest sort_order active option", example = "false") boolean isDefault
    ) {}
}
