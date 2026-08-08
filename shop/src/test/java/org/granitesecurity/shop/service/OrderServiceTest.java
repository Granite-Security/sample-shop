package org.granitesecurity.shop.service;

import org.granitesecurity.shop.domain.CustomerOrder;
import org.granitesecurity.shop.domain.OrderItem;
import org.granitesecurity.shop.domain.OutboxEvent;
import org.granitesecurity.shop.domain.Product;
import org.granitesecurity.shop.dto.PlaceOrderRequest;
import org.granitesecurity.shop.repository.CustomerOrderRepository;
import org.granitesecurity.shop.repository.OrderItemRepository;
import org.granitesecurity.shop.repository.OutboxRepository;
import org.granitesecurity.shop.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private CustomerOrderRepository customerOrderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxCaptor;

    @InjectMocks
    private OrderService orderService;

    private static final PlaceOrderRequest.DeliveryAddress ADDRESS = new PlaceOrderRequest.DeliveryAddress(
            "Alice Smith", "123 Main St", null, "Springfield", "IL", "62701", "USA");

    @Test
    void shouldPlaceOrderSuccessfully() {
        Product product1 = new Product("Widget", BigDecimal.valueOf(10.00), 50, 1L);
        product1.setId(1L);
        Product product2 = new Product("Gadget", BigDecimal.valueOf(20.00), 30, 1L);
        product2.setId(2L);

        when(productRepository.findAllById(List.of(1L, 2L)))
                .thenReturn(Flux.just(product1, product2));

        CustomerOrder savedOrder = order("testuser", "PENDING", BigDecimal.valueOf(70.00));
        savedOrder.setId(100L);
        savedOrder.setCreatedAt(Instant.now());

        when(customerOrderRepository.save(any(CustomerOrder.class))).thenReturn(Mono.just(savedOrder));

        when(orderItemRepository.saveAll(any(List.class)))
                .thenReturn(Flux.just(
                        new OrderItem(100L, 1L, 2, BigDecimal.valueOf(10.00)),
                        new OrderItem(100L, 2L, 3, BigDecimal.valueOf(20.00))
                ));

        when(productRepository.save(any(Product.class))).thenReturn(Mono.just(product1), Mono.just(product2));

        when(outboxRepository.save(any(OutboxEvent.class))).thenReturn(Mono.just(new OutboxEvent()));

        PlaceOrderRequest request = new PlaceOrderRequest(List.of(
                new PlaceOrderRequest.LineItem(1L, 2),
                new PlaceOrderRequest.LineItem(2L, 3)
        ), ADDRESS, "stripe");

        StepVerifier.create(orderService.placeOrder("testuser", request))
                .assertNext(response -> {
                    assert response.username().equals("testuser");
                    assert response.status().equals("PENDING");
                    assert response.total().compareTo(BigDecimal.valueOf(70.00)) == 0;
                    assert response.items().size() == 2;
                })
                .verifyComplete();

        verify(productRepository, times(2)).findAllById(List.of(1L, 2L));
        verify(productRepository, times(2)).save(any(Product.class));
        verify(customerOrderRepository).save(any(CustomerOrder.class));
        verify(orderItemRepository).saveAll(any(List.class));
        verify(outboxRepository).save(any(OutboxEvent.class));
    }

    /**
     * The tag is what keeps a consumer from reaching OrderPlaced by falling through the
     * branches for every other type on orders.events, so it is worth asserting rather
     * than trusting the payload builder.
     */
    @Test
    void shouldTagOrderPlacedEventAndCarryTheProvider() {
        Product product = new Product("Widget", BigDecimal.valueOf(10.00), 50, 1L);
        product.setId(1L);

        when(productRepository.findAllById(List.of(1L))).thenReturn(Flux.just(product));

        CustomerOrder savedOrder = new CustomerOrder("testuser", "PENDING", BigDecimal.valueOf(20.00), "CHF",
                "Alice Smith", "123 Main St", "", "Springfield", "IL", "62701", "USA");
        savedOrder.setId(101L);
        savedOrder.setCreatedAt(Instant.now());

        when(customerOrderRepository.save(any(CustomerOrder.class))).thenReturn(Mono.just(savedOrder));
        when(orderItemRepository.saveAll(any(List.class)))
                .thenReturn(Flux.just(new OrderItem(101L, 1L, 2, BigDecimal.valueOf(10.00))));
        when(productRepository.save(any(Product.class))).thenReturn(Mono.just(product));
        when(outboxRepository.save(any(OutboxEvent.class))).thenReturn(Mono.just(new OutboxEvent()));

        PlaceOrderRequest request = new PlaceOrderRequest(List.of(
                new PlaceOrderRequest.LineItem(1L, 2)
        ), ADDRESS, "paypal");

        StepVerifier.create(orderService.placeOrder("testuser", request))
                .expectNextCount(1)
                .verifyComplete();

        verify(outboxRepository).save(outboxCaptor.capture());
        OutboxEvent event = outboxCaptor.getValue();
        assert "OrderPlaced".equals(event.getEventType()) : "outbox row should be typed";
        assert event.getPayload().contains("\"eventType\":\"OrderPlaced\"")
                : "payload should carry the type a consumer dispatches on: " + event.getPayload();
        assert event.getPayload().contains("\"provider\":\"paypal\"")
                : "payload should carry the chosen provider: " + event.getPayload();
    }

    @Test
    void shouldRejectOrderWithoutProvider() {
        PlaceOrderRequest request = new PlaceOrderRequest(List.of(
                new PlaceOrderRequest.LineItem(1L, 2)
        ), ADDRESS, null);

        StepVerifier.create(orderService.placeOrder("testuser", request))
                .expectErrorMatches(e -> e instanceof ShopException
                        && e.getMessage().contains("must name a payment provider"))
                .verify();

        // Rejected before any lookup: naming no provider is not a partially valid order.
        verifyNoInteractions(productRepository, customerOrderRepository, orderItemRepository, outboxRepository);
    }

    @Test
    void shouldRejectEmptyOrder() {
        PlaceOrderRequest request = new PlaceOrderRequest(List.of(), ADDRESS, "stripe");

        StepVerifier.create(orderService.placeOrder("testuser", request))
                .expectErrorMatches(e -> e instanceof ShopException
                        && e.getMessage().equals("Order must contain at least one item"))
                .verify();

        verifyNoInteractions(productRepository, customerOrderRepository, orderItemRepository);
    }

    @Test
    void shouldRejectWhenProductNotFound() {
        when(productRepository.findAllById(List.of(999L)))
                .thenReturn(Flux.empty());

        PlaceOrderRequest request = new PlaceOrderRequest(List.of(
                new PlaceOrderRequest.LineItem(999L, 1)
        ), ADDRESS, "stripe");

        StepVerifier.create(orderService.placeOrder("testuser", request))
                .expectErrorMatches(e -> e instanceof ShopException
                        && e.getMessage().equals("Product not found: 999"))
                .verify();

        verify(customerOrderRepository, never()).save(any());
        verify(orderItemRepository, never()).saveAll(anyList());
    }

    @Test
    void shouldRejectWhenInsufficientStock() {
        Product product = new Product("Widget", BigDecimal.valueOf(10.00), 2, 1L);
        product.setId(1L);

        when(productRepository.findAllById(List.of(1L)))
                .thenReturn(Flux.just(product));

        PlaceOrderRequest request = new PlaceOrderRequest(List.of(
                new PlaceOrderRequest.LineItem(1L, 5)
        ), ADDRESS, "stripe");

        StepVerifier.create(orderService.placeOrder("testuser", request))
                .expectErrorMatches(e -> e instanceof ShopException
                        && e.getMessage().contains("Insufficient stock"))
                .verify();

        verify(customerOrderRepository, never()).save(any());
        verify(orderItemRepository, never()).saveAll(anyList());
    }

    @Test
    void shouldReturnOrdersForUser() {
        CustomerOrder order1 = order("testuser", "PENDING", BigDecimal.valueOf(30.00));
        order1.setId(10L);
        CustomerOrder order2 = order("testuser", "SHIPPED", BigDecimal.valueOf(50.00));
        order2.setId(20L);

        when(customerOrderRepository.countByUsername("testuser"))
                .thenReturn(Mono.just(2L));
        when(customerOrderRepository.findByUsernamePaged("testuser", 20, 0L))
                .thenReturn(Flux.just(order1, order2));

        when(orderItemRepository.findByOrderId(10L)).thenReturn(Flux.just(
                new OrderItem(10L, 1L, 3, BigDecimal.valueOf(10.00))
        ));
        when(orderItemRepository.findByOrderId(20L)).thenReturn(Flux.just(
                new OrderItem(20L, 2L, 1, BigDecimal.valueOf(50.00))
        ));
        when(productRepository.findAllById(any(java.util.List.class))).thenReturn(Flux.empty());

        StepVerifier.create(orderService.getOrdersForUser("testuser", 0, 20))
                .assertNext(result -> {
                    assert result.total() == 2;
                    assert result.items().size() == 2;
                    assert result.items().get(0).id().equals(10L);
                    assert result.items().get(0).items().size() == 1;
                    assert result.items().get(1).id().equals(20L);
                    assert result.items().get(1).items().size() == 1;
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnOrderByIdWithOwnershipCheck() {
        CustomerOrder order = order("testuser", "PENDING", BigDecimal.valueOf(25.00));
        order.setId(5L);
        order.setCreatedAt(Instant.now());

        when(customerOrderRepository.findById(5L)).thenReturn(Mono.just(order));
        when(orderItemRepository.findByOrderId(5L)).thenReturn(Flux.just(
                new OrderItem(5L, 1L, 1, BigDecimal.valueOf(25.00))
        ));
        when(productRepository.findAllById(any(java.util.List.class))).thenReturn(Flux.empty());

        StepVerifier.create(orderService.getOrder(5L, "testuser", false))
                .assertNext(r -> {
                    assert r.id().equals(5L);
                    assert r.username().equals("testuser");
                    assert r.items().get(0).productId().equals(1L);
                })
                .verifyComplete();
    }

    @Test
    void shouldRejectOrderForWrongUser() {
        CustomerOrder order = order("otheruser", "PENDING", BigDecimal.valueOf(25.00));
        order.setId(5L);

        when(customerOrderRepository.findById(5L)).thenReturn(Mono.just(order));

        StepVerifier.create(orderService.getOrder(5L, "testuser", false))
                .expectErrorMatches(e -> e instanceof ShopException
                        && e.getMessage().equals("Order not found: 5"))
                .verify();
    }

    @Test
    void shouldRejectNonExistentOrder() {
        when(customerOrderRepository.findById(999L)).thenReturn(Mono.empty());

        StepVerifier.create(orderService.getOrder(999L, "testuser", false))
                .expectErrorMatches(e -> e instanceof ShopException
                        && e.getMessage().equals("Order not found: 999"))
                .verify();
    }

    @Test
    void userRefundSucceedsWhenDeliveryFailed() {
        CustomerOrder order = order("testuser", "SHIPPED", BigDecimal.valueOf(25.00));
        order.setId(5L);
        order.setDeliveryStatus("FAILED");

        when(customerOrderRepository.findById(5L)).thenReturn(Mono.just(order));
        when(customerOrderRepository.save(any(CustomerOrder.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(outboxRepository.save(any(OutboxEvent.class))).thenReturn(Mono.just(new OutboxEvent()));
        when(orderItemRepository.findByOrderId(5L)).thenReturn(Flux.empty());
        when(productRepository.findAllById(any(java.util.List.class))).thenReturn(Flux.empty());

        StepVerifier.create(orderService.requestRefund(5L, "testuser", false))
                .assertNext(r -> {
                    assert r.id().equals(5L);
                    assert r.status().equals("RETURNED");
                })
                .verifyComplete();

        verify(customerOrderRepository).save(any(CustomerOrder.class));
        verify(outboxRepository).save(argThat(e -> "RefundRequested".equals(e.getEventType())
                && e.getPayload().contains("\"eventType\":\"RefundRequested\"")
                && e.getPayload().contains("\"orderId\":5")
                && e.getPayload().contains("\"username\":\"testuser\"")));
    }

    @Test
    void userRefundRejectedWhenStatusNotShipped() {
        CustomerOrder order = order("testuser", "PAID", BigDecimal.valueOf(25.00));
        order.setId(5L);
        order.setDeliveryStatus("FAILED");

        when(customerOrderRepository.findById(5L)).thenReturn(Mono.just(order));

        StepVerifier.create(orderService.requestRefund(5L, "testuser", false))
                .expectErrorMatches(e -> e instanceof ShopException se
                        && se.getStatus() == org.springframework.http.HttpStatus.CONFLICT
                        && se.getMessage().equals("Order is not eligible for a refund"))
                .verify();

        verify(customerOrderRepository, never()).save(any());
        verifyNoInteractions(outboxRepository);
    }

    @Test
    void userRefundRejectedWhenDeliveryStatusNotFailed() {
        CustomerOrder order = order("testuser", "SHIPPED", BigDecimal.valueOf(25.00));
        order.setId(5L);
        order.setDeliveryStatus("DELIVERED");

        when(customerOrderRepository.findById(5L)).thenReturn(Mono.just(order));

        StepVerifier.create(orderService.requestRefund(5L, "testuser", false))
                .expectErrorMatches(e -> e instanceof ShopException se
                        && se.getStatus() == org.springframework.http.HttpStatus.CONFLICT
                        && se.getMessage().equals("Order is not eligible for a refund"))
                .verify();

        verify(customerOrderRepository, never()).save(any());
        verifyNoInteractions(outboxRepository);
    }

    @Test
    void userRefundRejectedForNonOwner() {
        CustomerOrder order = order("otheruser", "SHIPPED", BigDecimal.valueOf(25.00));
        order.setId(5L);
        order.setDeliveryStatus("FAILED");

        when(customerOrderRepository.findById(5L)).thenReturn(Mono.just(order));

        StepVerifier.create(orderService.requestRefund(5L, "testuser", false))
                .expectErrorMatches(e -> e instanceof ShopException se
                        && se.getStatus() == org.springframework.http.HttpStatus.NOT_FOUND
                        && se.getMessage().equals("Order not found: 5"))
                .verify();

        verify(customerOrderRepository, never()).save(any());
        verifyNoInteractions(outboxRepository);
    }

    @Test
    void adminRefundSucceedsFromPaid() {
        CustomerOrder order = order("testuser", "PAID", BigDecimal.valueOf(25.00));
        order.setId(5L);

        when(customerOrderRepository.findById(5L)).thenReturn(Mono.just(order));
        when(customerOrderRepository.save(any(CustomerOrder.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(outboxRepository.save(any(OutboxEvent.class))).thenReturn(Mono.just(new OutboxEvent()));
        when(orderItemRepository.findByOrderId(5L)).thenReturn(Flux.empty());
        when(productRepository.findAllById(any(java.util.List.class))).thenReturn(Flux.empty());

        StepVerifier.create(orderService.requestRefund(5L, "admin", true))
                .assertNext(r -> { assert r.status().equals("RETURNED"); })
                .verifyComplete();

        verify(outboxRepository).save(argThat(e -> "RefundRequested".equals(e.getEventType())));
    }

    @Test
    void adminRefundSucceedsFromDelivered() {
        CustomerOrder order = order("testuser", "DELIVERED", BigDecimal.valueOf(25.00));
        order.setId(5L);
        order.setDeliveryStatus("DELIVERED");

        when(customerOrderRepository.findById(5L)).thenReturn(Mono.just(order));
        when(customerOrderRepository.save(any(CustomerOrder.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(outboxRepository.save(any(OutboxEvent.class))).thenReturn(Mono.just(new OutboxEvent()));
        when(orderItemRepository.findByOrderId(5L)).thenReturn(Flux.empty());
        when(productRepository.findAllById(any(java.util.List.class))).thenReturn(Flux.empty());

        StepVerifier.create(orderService.requestRefund(5L, "admin", true))
                .assertNext(r -> { assert r.status().equals("RETURNED"); })
                .verifyComplete();
    }

    @Test
    void adminRefundRejectedWhenPending() {
        CustomerOrder order = order("testuser", "PENDING", BigDecimal.valueOf(25.00));
        order.setId(5L);

        when(customerOrderRepository.findById(5L)).thenReturn(Mono.just(order));

        StepVerifier.create(orderService.requestRefund(5L, "admin", true))
                .expectErrorMatches(e -> e instanceof ShopException se
                        && se.getStatus() == org.springframework.http.HttpStatus.CONFLICT
                        && se.getMessage().equals("Order was never successfully paid"))
                .verify();

        verify(customerOrderRepository, never()).save(any());
        verifyNoInteractions(outboxRepository);
    }

    @Test
    void adminRefundRejectedWhenReimbursed() {
        CustomerOrder order = order("testuser", "REIMBURSED", BigDecimal.valueOf(25.00));
        order.setId(5L);

        when(customerOrderRepository.findById(5L)).thenReturn(Mono.just(order));

        StepVerifier.create(orderService.requestRefund(5L, "admin", true))
                .expectErrorMatches(e -> e instanceof ShopException se
                        && se.getStatus() == org.springframework.http.HttpStatus.CONFLICT
                        && se.getMessage().equals("Refund already requested"))
                .verify();

        verify(customerOrderRepository, never()).save(any());
        verifyNoInteractions(outboxRepository);
    }

    private static CustomerOrder order(String username, String status, BigDecimal total) {
        CustomerOrder order = new CustomerOrder();
        order.setUsername(username);
        order.setStatus(status);
        order.setTotal(total);
        order.setCreatedAt(Instant.now());
        order.setUpdatedAt(order.getCreatedAt());
        return order;
    }
}
