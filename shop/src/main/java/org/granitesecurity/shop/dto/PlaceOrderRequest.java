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
                requiredMode = Schema.RequiredMode.REQUIRED, example = "stripe") String provider,
        // Ids only, one per packaging group in the cart — the server reprices them
        // (docs/packaging/packaging.md D43). Required when anything in the cart needs a
        // box: an order whose packaging was never chosen is one nobody can pack.
        @Schema(description = "Chosen box per packaging group, from POST /api/shop/packaging/quote. "
                + "Required when the cart contains anything that needs packaging.")
        List<PackagingChoice> packaging,
        // Optional. Validated and repriced here from scratch — a preview is not a
        // reservation (docs/finance/vouchers.md V11), and between the two the code can
        // expire or be revoked. Case and surrounding space are normalised away (V12).
        @Schema(description = "Voucher code to apply, from POST /api/shop/vouchers/preview. "
                + "Optional; an unknown, expired, revoked or already-used code is a 400.",
                example = "SPRING25") String voucherCode
) {

    /**
     * The shape before vouchers existed — see the packaging constructor below for why
     * adding a component keeps every current caller working.
     */
    public PlaceOrderRequest(List<LineItem> items, DeliveryAddress address, String provider,
                             List<PackagingChoice> packaging) {
        this(items, address, provider, packaging, null);
    }

    /**
     * The shape before packaging existed.
     *
     * <p>A cart of bars needs no box and so sends no choices, and the seed data and the
     * older SPA builds never had an opinion either: a record's canonical constructor is
     * fixed-arity, but its deserialisation is by name, so those bodies keep working. An
     * order that <em>does</em> contain truffles and omits this is a 400 — see
     * OrderService.
     */
    public PlaceOrderRequest(List<LineItem> items, DeliveryAddress address, String provider) {
        this(items, address, provider, null, null);
    }
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
