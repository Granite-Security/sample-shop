package org.granitesecurity.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Request to place a new order")
public record PlaceOrderRequest(
        @Schema(description = "Items to order") List<LineItem> items,
        @Schema(description = "Delivery address") DeliveryAddress address,
        @Schema(description = "Payment provider id from GET /api/payments/providers. "
                + "Required: an order that names none is a 400, because payment cannot "
                + "pick one on the shopper's behalf once several are enabled.",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "stripe") String provider
) {
    @Schema(description = "Product and quantity pair")
    public record LineItem(
            @Schema(description = "Product ID", example = "1") Long productId,
            @Schema(description = "Quantity to order", example = "2") int quantity
    ) {}

    @Schema(description = "Delivery address snapshot")
    public record DeliveryAddress(
            @Schema(description = "Recipient name", example = "Alice Smith") String recipientName,
            @Schema(description = "Address line 1", example = "123 Main St") String addressLine1,
            @Schema(description = "Address line 2", example = "Apt 4B") String addressLine2,
            @Schema(description = "City", example = "Springfield") String city,
            @Schema(description = "State", example = "IL") String state,
            @Schema(description = "ZIP code", example = "62701") String zipCode,
            @Schema(description = "Country", example = "USA") String country
    ) {}
}
