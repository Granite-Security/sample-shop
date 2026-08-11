package org.granitesecurity.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * A cart to price packaging for. Nothing is stored — this is the question
 * "what would boxing this cost", asked before there is an order to attach it to.
 */
@Schema(description = "Cart to quote packaging for")
public record PackagingQuoteRequest(
        @Schema(description = "Items in the cart") List<PlaceOrderRequest.LineItem> items
) {
}
