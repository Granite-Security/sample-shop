package org.granitesecurity.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What a code is worth on this cart, or why it is worth nothing.
 *
 * <p>A refusal is a <strong>200 with {@code valid: false}</strong>, not a 404: the
 * request was well-formed, and the answer is something checkout has to render, not
 * an error. Only placement turns a refusal into a 400.
 */
@Schema(description = "Voucher priced against a cart")
public record VoucherPreviewResponse(
        @Schema(description = "The code, normalised to upper case", example = "SPRING25") String code,
        @Schema(description = "Whether it applies to this cart") boolean valid,
        @Schema(description = "Why it does not, when valid is false",
                example = "EXPIRED") String reason,
        @Schema(description = "Wording for the reason, ready to show", 
                example = "This voucher has expired") String message,
        @Schema(description = "The voucher's percentage", example = "10") Short percentOff,
        @Schema(description = "When it stops working") Instant validUntil,
        @Schema(description = "Goods subtotal — the discount base", example = "54.00") BigDecimal itemsTotal,
        @Schema(description = "What the voucher takes off", example = "5.40") BigDecimal discountTotal,
        @Schema(description = "Boxes, charged in full", example = "6.00") BigDecimal packagingTotal,
        @Schema(description = "What would be charged", example = "54.60") BigDecimal payableTotal,
        @Schema(description = "Currency of every amount here", example = "CHF") String currency
) {
}
