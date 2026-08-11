package org.granitesecurity.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The shopper's box choice for one packaging group.
 *
 * <p>Ids only. Prices, box counts and totals are the server's to compute — a client
 * that could send a packaging price could send zero (D43).
 */
@Schema(description = "Chosen packaging option for one packaging group")
public record PackagingChoice(
        @Schema(description = "Packaging group ID, from the quote", example = "1") Long groupId,
        @Schema(description = "Chosen packaging option ID, from the quote", example = "2") Long optionId
) {
}
