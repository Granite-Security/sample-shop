package org.granitesecurity.shop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Paginated list response wrapper")
public record PagedResult<T>(
        @Schema(description = "Page items") List<T> items,
        @Schema(description = "Total number of items across all pages", example = "42") long total,
        @Schema(description = "Current page number (0-based)", example = "0") int page,
        @Schema(description = "Page size", example = "20") int size
) {
    @JsonProperty("totalPages")
    @Schema(description = "Total number of pages", example = "3")
    public int totalPages() {
        return size > 0 ? (int) Math.ceil((double) total / size) : 0;
    }
}
