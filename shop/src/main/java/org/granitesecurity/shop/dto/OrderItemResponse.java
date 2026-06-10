package org.granitesecurity.shop.dto;

import java.math.BigDecimal;

public record OrderItemResponse(Long id, Long productId, Integer quantity, BigDecimal unitPrice) {
}
