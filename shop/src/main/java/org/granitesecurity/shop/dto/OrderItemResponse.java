package org.granitesecurity.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "Item within an order")
public record OrderItemResponse(
        @Schema(description = "Order item ID", example = "1") Long id,
        @Schema(description = "Product ID", example = "1") Long productId,
        @Schema(description = "Product name at time of order", example = "Wireless Headphones") String productName,
        @Schema(description = "Quantity ordered", example = "2") Integer quantity,
        @Schema(description = "Price per unit at time of order", example = "79.99") BigDecimal unitPrice
) {
}
