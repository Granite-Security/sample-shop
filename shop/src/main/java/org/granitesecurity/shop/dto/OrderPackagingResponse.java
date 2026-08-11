package org.granitesecurity.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * What an order was packed in, as returned with the order.
 *
 * <p>Prices are the frozen ones from placement, not today's catalogue: this is a
 * record of what was charged, and it must keep reading the same after the box is
 * repriced.
 */
@Schema(description = "Packaging charged on an order, one entry per group")
public record OrderPackagingResponse(
        @Schema(description = "Packaging group ID", example = "1") Long groupId,
        @Schema(description = "Packaging group code", example = "TRUFFLE") String groupCode,
        @Schema(description = "Packaging group name", example = "Truffles") String groupName,
        @Schema(description = "Packaging option ID", example = "2") Long optionId,
        @Schema(description = "Packaging option code", example = "PREMIUM") String optionCode,
        @Schema(description = "Packaging option name", example = "Premium gift box") String optionName,
        @Schema(description = "Number of boxes", example = "2") Integer quantity,
        @Schema(description = "Price per box at the time of the order", example = "6.00") BigDecimal unitPrice,
        @Schema(description = "quantity x unitPrice", example = "12.00") BigDecimal total
) {
}
