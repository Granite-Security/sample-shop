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

        eventConsumer.onPaymentEvent(json(Map.of("orderId", 1, "paymentId", "pay-123", "amount", 99.99)));

        assertEquals("PAID", order.getStatus());
    }

    @Test
    void paymentFailedTransitionsToPaymentFailed() {
        CustomerOrder order = orderWithStatus(OrderStatus.PENDING);
        when(customerOrderRepository.findById(1L)).thenReturn(Mono.just(order));
        when(customerOrderRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        eventConsumer.onPaymentEvent(json(Map.of("orderId", 1, "reason", "Insufficient funds")));

        assertEquals("PAYMENT_FAILED", order.getStatus());
    }

    @Test
    void shipmentDispatchedTransitionsToShipped() {
        CustomerOrder order = orderWithStatus(OrderStatus.PAID);
        when(customerOrderRepository.findById(1L)).thenReturn(Mono.just(order));
        when(customerOrderRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        eventConsumer.onShipmentEvent(json(Map.of("orderId", 1, "shipmentId", "ship-1", "carrier", "UPS")));

        assertEquals("SHIPPED", order.getStatus());
    }

    @Test
    void shipmentDeliveredTransitionsToDelivered() {
        CustomerOrder order = orderWithStatus(OrderStatus.SHIPPED);
        when(customerOrderRepository.findById(1L)).thenReturn(Mono.just(order));
        when(customerOrderRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        eventConsumer.onShipmentEvent(json(Map.of("orderId", 1, "shipmentId", "ship-1", "deliveredAt", "2026-06-13T12:00:00Z")));

        assertEquals("DELIVERED", order.getStatus());
    }

    @Test
    void duplicatePaymentReceivedIsIdempotent() {
        CustomerOrder order = orderWithStatus(OrderStatus.PAID);
        when(customerOrderRepository.findById(1L)).thenReturn(Mono.just(order));

        eventConsumer.onPaymentEvent(json(Map.of("orderId", 1, "paymentId", "pay-123", "amount", 99.99)));

        assertEquals("PAID", order.getStatus());
        verify(customerOrderRepository, never()).save(any());
    }

    @Test
    void unknownOrderDoesNotThrow() {
        when(customerOrderRepository.findById(99L)).thenReturn(Mono.empty());

        eventConsumer.onPaymentEvent(json(Map.of("orderId", 99, "paymentId", "pay-123", "amount", 99.99)));

        verify(customerOrderRepository, never()).save(any());
    }

    @Test
    void fullLifecycleInSequence() {
        CustomerOrder order = orderWithStatus(OrderStatus.PENDING);
        when(customerOrderRepository.findById(1L)).thenReturn(Mono.just(order));
        when(customerOrderRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        eventConsumer.onPaymentEvent(json(Map.of("orderId", 1, "paymentId", "pay-1", "amount", 99.99)));
        assertEquals("PAID", order.getStatus());

        eventConsumer.onShipmentEvent(json(Map.of("orderId", 1, "shipmentId", "ship-1", "carrier", "UPS")));
        assertEquals("SHIPPED", order.getStatus());

        eventConsumer.onShipmentEvent(json(Map.of("orderId", 1, "shipmentId", "ship-1", "deliveredAt", "2026-06-13T12:00:00Z")));
        assertEquals("DELIVERED", order.getStatus());

        verify(customerOrderRepository, times(3)).save(any());
    }

    @Test
    void deliveryFailedPersistsDeliveryStatus() {
        CustomerOrder order = orderWithStatus(OrderStatus.SHIPPED);
        when(customerOrderRepository.findById(1L)).thenReturn(Mono.just(order));
        when(customerOrderRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        eventConsumer.onDeliveryEvent(json(Map.of("orderId", 1, "status", "FAILED")));

        assertEquals("FAILED", order.getDeliveryStatus());
        assertEquals("SHIPPED", order.getStatus());
        verify(customerOrderRepository).save(any());
    }

    @Test
    void deliveryDispatchedPersistsDeliveryStatus() {
        CustomerOrder order = orderWithStatus(OrderStatus.PAID);
        when(customerOrderRepository.findById(1L)).thenReturn(Mono.just(order));
        when(customerOrderRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        eventConsumer.onDeliveryEvent(json(Map.of("orderId", 1, "status", "DISPATCHED")));

        assertEquals("SHIPPED", order.getStatus());
        assertEquals("DISPATCHED", order.getDeliveryStatus());
    }

    @Test
    void paymentRefundedTransitionsToReimbursed() {
        CustomerOrder order = orderWithStatus(OrderStatus.RETURNED);
        when(customerOrderRepository.findById(1L)).thenReturn(Mono.just(order));
        when(customerOrderRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        eventConsumer.onPaymentEvent(json(Map.of("orderId", 1, "status", "REFUNDED")));

        assertEquals("REIMBURSED", order.getStatus());
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
            super(repo, null, null, null);
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
