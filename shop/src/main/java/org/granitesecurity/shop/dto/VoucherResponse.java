package org.granitesecurity.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A voucher as the admin list shows it, including revoked and expired ones — a list
 * that hides what was withdrawn gives no way to see what happened.
 */
@Schema(description = "Voucher with its current standing")
public record VoucherResponse(
        Long id,
        String code,
        @Schema(example = "10") Short percentOff,
        Instant validFrom,
        Instant validUntil,
        Instant revokedAt,
        String description,
        String createdBy,
        Instant createdAt,
        @Schema(description = "How many shoppers have used it; one per shopper at most",
                example = "17") Long redemptions,
        @Schema(description = "Derived, not stored: REVOKED, SCHEDULED, EXPIRED or ACTIVE",
                example = "ACTIVE") String status
) {
}
