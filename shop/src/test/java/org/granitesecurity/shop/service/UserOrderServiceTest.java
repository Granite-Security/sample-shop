package org.granitesecurity.shop.service;

import org.granitesecurity.shop.client.PaymentClient;
import org.granitesecurity.shop.domain.CustomerOrder;
import org.granitesecurity.shop.domain.OutboxEvent;
import org.granitesecurity.shop.repository.CustomerOrderRepository;
import org.granitesecurity.shop.repository.OrderItemRepository;
import org.granitesecurity.shop.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserOrderServiceTest {

    private CustomerOrderRepository customerOrderRepository;
    private OrderItemRepository orderItemRepository;
    private OutboxRepository outboxRepository;
    private PaymentClient paymentClient;
    private UserOrderService userOrderService;

    @BeforeEach
    void setUp() {
        customerOrderRepository = mock(CustomerOrderRepository.class);
        orderItemRepository = mock(OrderItemRepository.class);
        outboxRepository = mock(OutboxRepository.class);
        paymentClient = mock(PaymentClient.class);
        userOrderService = new UserOrderService(customerOrderRepository, orderItemRepository,
                outboxRepository, paymentClient);
    }

    private CustomerOrder order(Long id, String status) {
        CustomerOrder order = new CustomerOrder("alice", status, BigDecimal.TEN,
                "Alice", "Line 1", "", "Town", "", "1234", "NL");
        order.setId(id);
        return order;
    }

    private void givenOrders(CustomerOrder... orders) {
        when(customerOrderRepository.findByUsername("alice")).thenReturn(Flux.just(orders));
    }

    private void givenPaymentStatuses(Map<Long, String> statuses) {
        when(paymentClient.statusesByOrderIds(anyCollection())).thenReturn(Mono.just(statuses));
    }

    @Test
    void aUserWithNoOrdersIsEligible() {
        when(customerOrderRepository.findByUsername("alice")).thenReturn(Flux.empty());
        givenPaymentStatuses(Map.of());

        StepVerifier.create(userOrderService.purgeEligibility("alice"))
                .assertNext(result -> {
                    assertThat(result.eligible()).isTrue();
                    assertThat(result.orderIds()).isEmpty();
                    assertThat(result.paidOrderCount()).isZero();
                })
                .verifyComplete();
    }

    @Test
    void ordersWithOnlyCreatedPaymentsAreEligible() {
        givenOrders(order(1L, "PENDING"), order(2L, "PENDING"));
        givenPaymentStatuses(Map.of(1L, "CREATED", 2L, "CREATED"));

        StepVerifier.create(userOrderService.purgeEligibility("alice"))
                .assertNext(result -> {
                    assertThat(result.eligible()).isTrue();
                    assertThat(result.orderIds()).containsExactly(1L, 2L);
                    assertThat(result.paidOrderCount()).isZero();
                })
                .verifyComplete();
    }

    @Test
    void aSucceededPaymentBlocksDeletion() {
        givenOrders(order(1L, "PENDING"), order(2L, "PAID"));
        givenPaymentStatuses(Map.of(1L, "CREATED", 2L, "SUCCEEDED"));

        StepVerifier.create(userOrderService.purgeEligibility("alice"))
                .assertNext(result -> {
                    assertThat(result.eligible()).isFalse();
                    assertThat(result.paidOrderCount()).isEqualTo(1);
                })
                .verifyComplete();
    }

    // §4.2: money moved in both directions and Stripe keeps both records.
    @Test
    void aRefundedPaymentAlsoBlocksDeletion() {
        givenOrders(order(1L, "REIMBURSED"));
        givenPaymentStatuses(Map.of(1L, "REFUNDED"));

        StepVerifier.create(userOrderService.purgeEligibility("alice"))
                .assertNext(result -> assertThat(result.eligible()).isFalse())
                .verifyComplete();
    }

    // §2.3: CANCELLED is reachable from PAID, so order status says nothing
    // about whether money moved — only the payment status does.
    @Test
    void aCancelledOrderWhosePaymentSucceededStillBlocksDeletion() {
        givenOrders(order(1L, "CANCELLED"));
        givenPaymentStatuses(Map.of(1L, "SUCCEEDED"));

        StepVerifier.create(userOrderService.purgeEligibility("alice"))
                .assertNext(result -> assertThat(result.eligible()).isFalse())
                .verifyComplete();
    }

    @Test
    void anOrderWithNoPaymentRowCountsAsNoMoneyMoved() {
        givenOrders(order(1L, "PENDING"));
        givenPaymentStatuses(Map.of());

        StepVerifier.create(userOrderService.purgeEligibility("alice"))
                .assertNext(result -> assertThat(result.eligible()).isTrue())
                .verifyComplete();
    }

    @Test
    void purgeDeletesOrdersAndPublishesOrdersPurged() {
        givenOrders(order(1L, "PENDING"), order(2L, "PENDING"));
        givenPaymentStatuses(Map.of(1L, "CREATED"));
        when(orderItemRepository.deleteByOrderIdIn(anyCollection())).thenReturn(Mono.just(3L));
        when(customerOrderRepository.deleteByIdIn(anyCollection())).thenReturn(Mono.just(2L));
        when(outboxRepository.save(any(OutboxEvent.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(userOrderService.purgeOrders("alice"))
                .assertNext(result -> assertThat(result.deletedOrderIds()).containsExactly(1L, 2L))
                .verifyComplete();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo("OrdersPurged");
        assertThat(event.getAggregateId()).isEqualTo("alice");
        assertThat(event.getPayload()).contains("\"orderIds\":[1,2]");
    }

    // Defence in depth: profile checks before calling, but a user can place an
    // order between that check and this call (§4.2, "the race"). Deleting a paid
    // order is the outcome §9 explicitly rejected.
    @Test
    void purgeRefusesWhenTheUserHasAPaidOrder() {
        givenOrders(order(1L, "PAID"));
        givenPaymentStatuses(Map.of(1L, "SUCCEEDED"));

        StepVerifier.create(userOrderService.purgeOrders("alice"))
                .expectErrorSatisfies(error -> assertThat(((ShopException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .verify();

        verify(customerOrderRepository, never()).deleteByIdIn(anyCollection());
        verify(outboxRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void purgingAUserWithNoOrdersPublishesNothing() {
        when(customerOrderRepository.findByUsername("alice")).thenReturn(Flux.empty());
        givenPaymentStatuses(Map.of());

        StepVerifier.create(userOrderService.purgeOrders("alice"))
                .assertNext(result -> assertThat(result.deletedOrderIds()).isEmpty())
                .verifyComplete();

        verify(outboxRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void aPaymentOutageIsAnErrorNotAnEmptyResult() {
        givenOrders(order(1L, "PENDING"));
        when(paymentClient.statusesByOrderIds(anyCollection()))
                .thenReturn(Mono.error(new IllegalStateException("payment unreachable")));

        StepVerifier.create(userOrderService.purgeOrders("alice"))
                .expectError(IllegalStateException.class)
                .verify();

        verify(customerOrderRepository, never()).deleteByIdIn(anyCollection());
    }

    @Test
    void ordersAreNotQueriedFromPaymentWhenThereAreNone() {
        when(customerOrderRepository.findByUsername("alice")).thenReturn(Flux.empty());
        givenPaymentStatuses(Map.of());

        StepVerifier.create(userOrderService.purgeEligibility("alice")).expectNextCount(1).verifyComplete();

        verify(paymentClient).statusesByOrderIds(List.of());
    }
}
