package org.granitesecurity.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Schema(description = "Order with items and totals")
public record OrderResponse(
        @Schema(description = "Order ID", example = "1") Long id,
        @Schema(description = "Username who placed the order", example = "alice") String username,
        @Schema(description = "Order status", example = "PENDING") String status,
        @Schema(description = "Order total", example = "159.98") BigDecimal total,
        @Schema(description = "Currency the total is denominated in", example = "CHF") String currency,
        @Schema(description = "When the order was created") Instant createdAt,
        @Schema(description = "Line items in the order") List<OrderItemResponse> items,
        @Schema(description = "Payment provider handling this order, once payment has opened one",
                example = "stripe") String provider,
        @Schema(description = "What the frontend needs to complete payment; shape depends on the "
                + "provider's confirmation mode. Null here — the SPA fills it from "
                + "GET /api/payments/intent/{orderId}.") Map<String, Object> providerPayload,
        @Schema(description = "Deprecated alias for providerPayload.clientSecret. Null here for the "
                + "same reason; kept until ui-demo migrates.",
                example = "pi_xxx_secret_yyy") String clientSecret,
        @Schema(description = "Delivery address") DeliveryAddress address,
        @Schema(description = "How much of total is packaging", example = "12.00") BigDecimal packagingTotal,
        @Schema(description = "What the order was packed in, one entry per packaging group")
        List<OrderPackagingResponse> packaging,
        // The voucher as it was applied, snapshotted at placement (vouchers.md V5).
        // total is already net of discountTotal — it has always meant "the amount
        // payable" and still does (V4), which is why nothing downstream changed.
        @Schema(description = "Voucher applied at placement, or null", example = "SPRING25")
        String voucherCode,
        @Schema(description = "The voucher's percentage, a label for the amount below", example = "10")
        Short discountPercent,
        @Schema(description = "How much the voucher took off. Already subtracted from total",
                example = "5.40") BigDecimal discountTotal
) {

    /** The shape before vouchers existed, reading as an order placed without one. */
    public OrderResponse(Long id, String username, String status, BigDecimal total, String currency,
                         Instant createdAt, List<OrderItemResponse> items, String provider,
                         Map<String, Object> providerPayload, String clientSecret,
                         DeliveryAddress address, BigDecimal packagingTotal,
                         List<OrderPackagingResponse> packaging) {
        this(id, username, status, total, currency, createdAt, items, provider, providerPayload,
                clientSecret, address, packagingTotal, packaging, null, null, BigDecimal.ZERO);
    }

    /**
     * The shape before packaging existed, for callers that construct one without.
     * Reads as an order that needed no boxes, which is what every order placed before
     * packaging was.
     */
    public OrderResponse(Long id, String username, String status, BigDecimal total, String currency,
                         Instant createdAt, List<OrderItemResponse> items, String provider,
                         Map<String, Object> providerPayload, String clientSecret,
                         DeliveryAddress address) {
        this(id, username, status, total, currency, createdAt, items, provider, providerPayload,
                clientSecret, address, BigDecimal.ZERO, List.of(), null, null, BigDecimal.ZERO);
    }
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
