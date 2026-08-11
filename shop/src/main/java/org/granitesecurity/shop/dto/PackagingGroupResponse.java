package org.granitesecurity.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "Packaging group with the options it allows")
public record PackagingGroupResponse(
        @Schema(description = "Packaging group ID", example = "1") Long id,
        @Schema(description = "Stable code", example = "TRUFFLE") String code,
        @Schema(description = "Display name", example = "Truffles") String name,
        @Schema(description = "Description") String description,
        @Schema(description = "Allowed options and how many fit, retired ones included")
        List<AllowedOption> options
) {

    @Schema(description = "One option this group allows, with its capacity")
    public record AllowedOption(
            @Schema(description = "Packaging option ID", example = "2") Long optionId,
            @Schema(description = "Stable code", example = "PREMIUM") String code,
            @Schema(description = "Display name", example = "Premium gift box") String name,
            @Schema(description = "Price per box", example = "6.00") BigDecimal price,
            @Schema(description = "Still offered", example = "true") boolean active,
            @Schema(description = "Units of this group per box", example = "12") int capacity
    ) {}
}
