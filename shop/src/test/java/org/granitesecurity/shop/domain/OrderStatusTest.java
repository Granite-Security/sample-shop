package org.granitesecurity.shop.domain;

import org.granitesecurity.shop.service.ShopException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderStatusTest {

    @Test
    void pendingCanTransitionToPaid() {
        assertTrue(OrderStatus.PENDING.canTransitionTo(OrderStatus.PAID));
    }

    @Test
    void pendingCanTransitionToPaymentFailed() {
        assertTrue(OrderStatus.PENDING.canTransitionTo(OrderStatus.PAYMENT_FAILED));
    }

    @Test
    void pendingCanTransitionToCancelled() {
        assertTrue(OrderStatus.PENDING.canTransitionTo(OrderStatus.CANCELLED));
    }

    @Test
    void pendingCannotTransitionToShipped() {
        assertFalse(OrderStatus.PENDING.canTransitionTo(OrderStatus.SHIPPED));
    }

    @Test
    void paidCanTransitionToShipped() {
        assertTrue(OrderStatus.PAID.canTransitionTo(OrderStatus.SHIPPED));
    }

    @Test
    void paidCanTransitionToCancelled() {
        assertTrue(OrderStatus.PAID.canTransitionTo(OrderStatus.CANCELLED));
    }

    @Test
    void paidCanTransitionToReturned() {
        assertTrue(OrderStatus.PAID.canTransitionTo(OrderStatus.RETURNED));
    }

    @Test
    void shippedCanTransitionToDelivered() {
        assertTrue(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.DELIVERED));
    }

    @Test
    void shippedCanTransitionToReturned() {
        assertTrue(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.RETURNED));
    }

    @Test
    void deliveredCanTransitionToReturned() {
        assertTrue(OrderStatus.DELIVERED.canTransitionTo(OrderStatus.RETURNED));
    }

    @Test
    void returnedCanTransitionToReimbursed() {
        assertTrue(OrderStatus.RETURNED.canTransitionTo(OrderStatus.REIMBURSED));
    }

    @Test
    void terminalStatesHaveNoTransitions() {
        assertFalse(OrderStatus.CANCELLED.canTransitionTo(OrderStatus.PENDING));
        assertFalse(OrderStatus.REIMBURSED.canTransitionTo(OrderStatus.DELIVERED));
    }

    @Test
    void transitionToRejectsInvalid() {
        var ex = assertThrows(ShopException.class,
                () -> OrderStatus.PENDING.transitionTo(OrderStatus.SHIPPED));
        assertTrue(ex.getMessage().contains("Cannot transition"));
    }

    @Test
    void transitionToAcceptsValid() {
        assertEquals(OrderStatus.PAID, OrderStatus.PENDING.transitionTo(OrderStatus.PAID));
    }

    @Test
    void stateCannotTransitionToItself() {
        for (OrderStatus status : OrderStatus.values()) {
            assertFalse(status.canTransitionTo(status),
                    status + " should not be able to transition to itself");
        }
    }
}
