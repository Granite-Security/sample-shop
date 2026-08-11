package org.granitesecurity.shop.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.granitesecurity.shop.domain.CustomerOrder;
import org.granitesecurity.shop.domain.OrderStatus;
import org.granitesecurity.shop.repository.CustomerOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventConsumerTest {

    @Mock
    private CustomerOrderRepository customerOrderRepository;

    private OrderServiceStub orderService;
    private EventConsumer eventConsumer;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceStub(customerOrderRepository);
        eventConsumer = new EventConsumer(orderService);
    }

    @Test
    void paymentReceivedTransitionsToPaid() {
        CustomerOrder order = orderWithStatus(OrderStatus.PENDING);
        when(customerOrderRepository.findById(1L)).thenReturn(Mono.just(order));
        when(customerOrderRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        eventConsumer.onPaymentEvent(json(Map.of("orderId", 1, "paymentId", "pay-123", "amount", 99.99))).block();

        assertEquals("PAID", order.getStatus());
    }

    @Test
    void paymentFailedTransitionsToPaymentFailed() {
        CustomerOrder order = orderWithStatus(OrderStatus.PENDING);
        when(customerOrderRepository.findById(1L)).thenReturn(Mono.just(order));
        when(customerOrderRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        eventConsumer.onPaymentEvent(json(Map.of("orderId", 1, "reason", "Insufficient funds"))).block();

        assertEquals("PAYMENT_FAILED", order.getStatus());
    }

    @Test
    void shipmentDispatchedTransitionsToShipped() {
        CustomerOrder order = orderWithStatus(OrderStatus.PAID);
        when(customerOrderRepository.findById(1L)).thenReturn(Mono.just(order));
        when(customerOrderRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        eventConsumer.onShipmentEvent(json(Map.of("orderId", 1, "shipmentId", "ship-1", "carrier", "UPS"))).block();

        assertEquals("SHIPPED", order.getStatus());
    }

    @Test
    void shipmentDeliveredTransitionsToDelivered() {
        CustomerOrder order = orderWithStatus(OrderStatus.SHIPPED);
        when(customerOrderRepository.findById(1L)).thenReturn(Mono.just(order));
        when(customerOrderRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        eventConsumer.onShipmentEvent(json(Map.of("orderId", 1, "shipmentId", "ship-1", "deliveredAt", "2026-06-13T12:00:00Z"))).block();

        assertEquals("DELIVERED", order.getStatus());
    }

    @Test
    void duplicatePaymentReceivedIsIdempotent() {
        CustomerOrder order = orderWithStatus(OrderStatus.PAID);
        when(customerOrderRepository.findById(1L)).thenReturn(Mono.just(order));

        eventConsumer.onPaymentEvent(json(Map.of("orderId", 1, "paymentId", "pay-123", "amount", 99.99))).block();

        assertEquals("PAID", order.getStatus());
        verify(customerOrderRepository, never()).save(any());
    }

    @Test
    void unknownOrderDoesNotThrow() {
        when(customerOrderRepository.findById(99L)).thenReturn(Mono.empty());

        eventConsumer.onPaymentEvent(json(Map.of("orderId", 99, "paymentId", "pay-123", "amount", 99.99))).block();

        verify(customerOrderRepository, never()).save(any());
    }

    @Test
    void fullLifecycleInSequence() {
        CustomerOrder order = orderWithStatus(OrderStatus.PENDING);
        when(customerOrderRepository.findById(1L)).thenReturn(Mono.just(order));
        when(customerOrderRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        eventConsumer.onPaymentEvent(json(Map.of("orderId", 1, "paymentId", "pay-1", "amount", 99.99))).block();
        assertEquals("PAID", order.getStatus());

        eventConsumer.onShipmentEvent(json(Map.of("orderId", 1, "shipmentId", "ship-1", "carrier", "UPS"))).block();
        assertEquals("SHIPPED", order.getStatus());

        eventConsumer.onShipmentEvent(json(Map.of("orderId", 1, "shipmentId", "ship-1", "deliveredAt", "2026-06-13T12:00:00Z"))).block();
        assertEquals("DELIVERED", order.getStatus());

        verify(customerOrderRepository, times(3)).save(any());
    }

    @Test
    void deliveryFailedPersistsDeliveryStatus() {
        CustomerOrder order = orderWithStatus(OrderStatus.SHIPPED);
        when(customerOrderRepository.findById(1L)).thenReturn(Mono.just(order));
        when(customerOrderRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        eventConsumer.onDeliveryEvent(json(Map.of("orderId", 1, "status", "FAILED"))).block();

        assertEquals("FAILED", order.getDeliveryStatus());
        assertEquals("SHIPPED", order.getStatus());
        verify(customerOrderRepository).save(any());
    }

    @Test
    void deliveryDispatchedPersistsDeliveryStatus() {
        CustomerOrder order = orderWithStatus(OrderStatus.PAID);
        when(customerOrderRepository.findById(1L)).thenReturn(Mono.just(order));
        when(customerOrderRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        eventConsumer.onDeliveryEvent(json(Map.of("orderId", 1, "status", "DISPATCHED"))).block();

        assertEquals("SHIPPED", order.getStatus());
        assertEquals("DISPATCHED", order.getDeliveryStatus());
    }

    @Test
    void paymentRefundedTransitionsToReimbursed() {
        CustomerOrder order = orderWithStatus(OrderStatus.RETURNED);
        when(customerOrderRepository.findById(1L)).thenReturn(Mono.just(order));
        when(customerOrderRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        eventConsumer.onPaymentEvent(json(Map.of("orderId", 1, "status", "REFUNDED"))).block();

        assertEquals("REIMBURSED", order.getStatus());
    }

    /**
     * The distinction the retry policy turns on: unparseable is permanent, so it must be
     * a MalformedEventException the pipeline can route straight to the dead-letter topic
     * instead of retrying it four times first.
     */
    @Test
    void unparseableMessageFailsAsMalformed() {
        StepVerifier.create(eventConsumer.onPaymentEvent("{not json"))
                .expectError(MalformedEventException.class)
                .verify();
    }

    /**
     * The regression this refactor exists for. The handler used to block inside a
     * try/catch, so a database failure was logged and the offset committed — the money
     * had moved and the order stayed PENDING for good. The error must now escape.
     */
    @Test
    void databaseFailurePropagatesInsteadOfBeingSwallowed() {
        when(customerOrderRepository.findById(1L))
                .thenReturn(Mono.error(new RuntimeException("connection reset")));

        StepVerifier.create(eventConsumer.onPaymentEvent(
                        json(Map.of("orderId", 1, "status", "SUCCEEDED"))))
                .expectErrorMessage("connection reset")
                .verify();
    }

    /** A transient failure is not a MalformedEventException, so the pipeline retries it. */
    @Test
    void databaseFailureIsNotClassifiedAsMalformed() {
        when(customerOrderRepository.findById(1L))
                .thenReturn(Mono.error(new RuntimeException("connection reset")));

        StepVerifier.create(eventConsumer.onPaymentEvent(
                        json(Map.of("orderId", 1, "status", "SUCCEEDED"))))
                .expectErrorMatches(error -> !(error instanceof MalformedEventException))
                .verify();
    }

    private static CustomerOrder orderWithStatus(OrderStatus status) {
        CustomerOrder order = new CustomerOrder();
        order.setUsername("user");
        order.setStatus(status.name());
        order.setTotal(BigDecimal.valueOf(99.99));
        order.setId(1L);
        return order;
    }

    private static String json(Map<?, ?> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static class OrderServiceStub extends org.granitesecurity.shop.service.OrderService {
        private final CustomerOrderRepository repo;

        OrderServiceStub(CustomerOrderRepository repo) {
            super(repo, null, null, null, null, null, null, null);
            this.repo = repo;
        }

        @Override
        public Mono<Void> updateOrderStatus(Long orderId, OrderStatus targetStatus) {
            return repo.findById(orderId).flatMap(order -> {
                OrderStatus current = OrderStatus.valueOf(order.getStatus());
                if (!current.canTransitionTo(targetStatus)) {
                    return Mono.empty();
                }
                order.setStatus(targetStatus.name());
                order.setUpdatedAt(java.time.Instant.now());
                return repo.save(order).then();
            });
        }
    }
}
