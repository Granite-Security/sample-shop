package org.granitesecurity.shop.domain;

import org.granitesecurity.shop.service.ShopException;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.Set;

public enum OrderStatus {
    PENDING,
    PAID,
    SHIPPED,
    DELIVERED,
    PAYMENT_FAILED,
    CANCELLED,
    RETURNED,
    REIMBURSED;

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.of(
            PENDING,        Set.of(PAID, PAYMENT_FAILED, CANCELLED),
            PAID,           Set.of(SHIPPED, CANCELLED, RETURNED),
            SHIPPED,        Set.of(DELIVERED, RETURNED),
            DELIVERED,      Set.of(RETURNED),
            PAYMENT_FAILED, Set.of(PENDING),
            CANCELLED,      Set.of(),
            RETURNED,       Set.of(REIMBURSED),
            // REIMBURSED is terminal in the happy path, but not absolutely: payment
            // records a refund as succeeded when the provider accepts it, and the
            // provider can still fail it afterwards at the bank. PaymentRefundFailed
            // walks the order back to RETURNED — the refund was still requested, it
            // just did not complete — from where the existing RETURNED -> REIMBURSED
            // transition lets a retry finish the job.
            REIMBURSED,     Set.of(RETURNED)
    );

    public boolean canTransitionTo(OrderStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    public OrderStatus transitionTo(OrderStatus target) {
        if (!canTransitionTo(target)) {
            throw new ShopException(
                    "Cannot transition from " + this + " to " + target,
                    HttpStatus.BAD_REQUEST,
                    "InvalidStatusTransition"
            );
        }
        return target;
    }
}
