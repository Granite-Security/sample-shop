package org.granitesecurity.delivery.dto;

import java.util.List;

/**
 * Mirrors {@code shop}'s wrapper of the same name, field for field, because both
 * SPAs type every paginated response against a single {@code PagedResult<T>}
 * interface. There is no shared module between services, so this is a deliberate
 * copy rather than a dependency — the shape is the contract, not the class.
 *
 * @param items the rows on this page
 * @param total rows matching the filters across all pages, not just this one
 * @param page  zero-based
 * @param size  the clamped size actually used, which may be smaller than asked for
 */
public record PagedResult<T>(
        List<T> items,
        long total,
        int page,
        int size
) {}
