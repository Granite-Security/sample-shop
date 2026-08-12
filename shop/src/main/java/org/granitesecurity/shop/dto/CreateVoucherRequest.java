package org.granitesecurity.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A voucher to create. ADMIN only — this decides what future shoppers are charged.
 */
@Schema(description = "Voucher to create")
public record CreateVoucherRequest(
        @Schema(description = "Code shoppers will type; stored and matched upper-case",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "SPRING25") String code,
        @Schema(description = "Whole percent off the goods subtotal, 1-100",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "10") Short percentOff,
        @Schema(description = "When it starts working. Defaults to now") Instant validFrom,
        @Schema(description = "When it stops working. Required — a voucher with no expiry is a "
                + "permanent price cut wearing a code",
                requiredMode = Schema.RequiredMode.REQUIRED) Instant validUntil,
        @Schema(description = "What this campaign is, for the admin list",
                example = "Spring newsletter") String description
) {
}
