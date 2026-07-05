package org.granitesecurity.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(description = "Order with items and totals")
public record OrderResponse(
        @Schema(description = "Order ID", example = "1") Long id,
        @Schema(description = "Username who placed the order", example = "alice") String username,
        @Schema(description = "Order status", example = "PENDING") String status,
        @Schema(description = "Order total", example = "159.98") BigDecimal total,
        @Schema(description = "When the order was created") Instant createdAt,
        @Schema(description = "Line items in the order") List<OrderItemResponse> items,
        @Schema(description = "Stripe client secret for payment", example = "pi_xxx_secret_yyy") String clientSecret,
        @Schema(description = "Delivery address") DeliveryAddress address
) {
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
