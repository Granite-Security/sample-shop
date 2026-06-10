package org.granitesecurity.shop.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        String username,
        String status,
        BigDecimal total,
        Instant createdAt,
        List<OrderItemResponse> items
) {
}
