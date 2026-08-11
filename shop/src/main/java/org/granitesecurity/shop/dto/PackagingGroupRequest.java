package org.granitesecurity.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request to create or update a packaging group")
public record PackagingGroupRequest(
        // Immutable in practice on update — the code is what changelogs, events and
        // fulfilment refer to, so renaming it would orphan them.
        @Schema(description = "Stable code, uppercase", example = "TRUFFLE") String code,
        @Schema(description = "Display name shown at checkout", example = "Truffles") String name,
        @Schema(description = "Description shown next to the choice") String description
) {
}
