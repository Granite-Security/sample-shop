package org.granitesecurity.shop.dto;

import java.util.List;

/**
 * @param eligible       true iff no order of theirs has a payment in SUCCEEDED
 *                       or REFUNDED (docs/users/blocking-users.md §4.2)
 * @param orderIds       every order id belonging to the user — what a purge
 *                       would delete
 * @param paidOrderCount how many of those moved money; what the admin is told
 *                       when the answer is "blocked instead"
 */
public record PurgeEligibility(boolean eligible, List<Long> orderIds, int paidOrderCount) {
}
