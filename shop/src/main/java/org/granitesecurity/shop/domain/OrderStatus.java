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
            REIMBURSED,     Set.of()
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
