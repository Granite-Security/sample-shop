package org.granitesecurity.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * "This box holds this many of that group." The one number that belongs to neither
 * side alone (D39).
 */
@Schema(description = "Capacity of one option for one group")
public record PackagingCapacityRequest(
        @Schema(description = "Packaging option ID", example = "2") Long optionId,
        @Schema(description = "Units of this group that fit in one box", example = "12") Integer capacity
) {
}
