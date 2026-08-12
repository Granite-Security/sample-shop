package org.granitesecurity.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * A cart and a code, asked "what would this be worth". Stores nothing and reserves
 * nothing (docs/finance/vouchers.md V11) — the same shape as
 * {@link PackagingQuoteRequest}, for the same reason: checkout has to show a number
 * before there is an order to attach it to.
 *
 * <p>The packaging choices are here because the discount base excludes boxes (V7)
 * but the minimum-payable check does not: a cart whose boxes carry it over the
 * provider minimum must not be refused as if they were free.
 */
@Schema(description = "Cart and voucher code to price")
public record VoucherPreviewRequest(
        @Schema(description = "Voucher code, in any case", example = "spring25") String code,
        @Schema(description = "Items in the cart") List<PlaceOrderRequest.LineItem> items,
        @Schema(description = "Chosen box per packaging group, as sent to POST /api/shop/orders")
        List<PackagingChoice> packaging
) {
}
