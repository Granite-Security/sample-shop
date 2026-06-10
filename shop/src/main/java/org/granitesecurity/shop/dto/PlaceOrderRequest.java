package org.granitesecurity.shop.dto;

import java.util.List;

public record PlaceOrderRequest(
        List<LineItem> items
) {
    public record LineItem(
            Long productId,
            int quantity
    ) {}
}
