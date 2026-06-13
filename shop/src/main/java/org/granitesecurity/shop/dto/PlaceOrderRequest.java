package org.granitesecurity.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Request to place a new order")
public record PlaceOrderRequest(
        @Schema(description = "Items to order") List<LineItem> items
) {
    @Schema(description = "Product and quantity pair")
    public record LineItem(
            @Schema(description = "Product ID", example = "1") Long productId,
            @Schema(description = "Quantity to order", example = "2") int quantity
    ) {}
}
