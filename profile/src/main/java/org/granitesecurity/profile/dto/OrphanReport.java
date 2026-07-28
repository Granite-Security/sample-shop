package org.granitesecurity.profile.dto;

import java.util.List;

/**
 * A read-only reconciliation report (docs/users/blocking-users.md §8 Phase 6).
 *
 * <p>The cascade behind a hard delete spans four databases and rides an
 * at-least-once event, so a half-completed purge is possible and otherwise
 * leaves no trace. This is what makes it visible. It deletes nothing.
 */
public record OrphanReport(
        /* customer_order rows whose username matches no auth-server user. */
        List<OrphanedOrders> orphanedOrders,
        /* payment rows whose order_id matches no order in shop. */
        List<Long> orphanedPaymentOrderIds,
        /* delivery rows whose order_id matches no order in shop. */
        List<Long> orphanedDeliveryOrderIds) {

    public record OrphanedOrders(String username, long orderCount) {}

    public boolean isClean() {
        return orphanedOrders.isEmpty()
                && orphanedPaymentOrderIds.isEmpty()
                && orphanedDeliveryOrderIds.isEmpty();
    }
}
