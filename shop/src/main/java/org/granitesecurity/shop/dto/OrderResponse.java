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
        @Schema(description = "Line items in the order") List<OrderItemResponse> items
) {
}
