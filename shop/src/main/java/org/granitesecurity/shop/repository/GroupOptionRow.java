package org.granitesecurity.shop.repository;

import java.math.BigDecimal;

/**
 * One {@code (group, option)} pairing, joined with both sides — everything pricing a
 * cart needs about one box, in one row.
 *
 * @param capacity how many units of this group fit in this option's box; the number
 *                 that only exists for the pair
 */
public record GroupOptionRow(
        Long groupId, String groupCode, String groupName, String groupDescription,
        Long optionId, String optionCode, String optionName, String optionDescription,
        String imageUrl, BigDecimal price, BigDecimal unitCost, Integer sortOrder,
        Boolean active, Integer capacity) {
}
