package org.granitesecurity.payment.consumer;

import org.granitesecurity.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrdersPurgedConsumerTest {

    @Mock
    private PaymentService paymentService;

    private OrderPlacedConsumer consumer() {
        return new OrderPlacedConsumer(paymentService);
    }

    @Test
    void purgesTheOrderIdsCarriedByTheEvent() {
        when(paymentService.purgeOrders(anyCollection())).thenReturn(Mono.empty());

        consumer().onOrderPlaced("""
                {"eventType":"OrdersPurged","username":"alice","orderIds":[1,2,3]}
                """);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(paymentService).purgeOrders(captor.capture());
        assertThat(captor.getValue()).containsExactly(1L, 2L, 3L);
    }

    // OrdersPurged has no "orderId" — if it fell through to the OrderPlaced
    // path it would be logged as malformed and silently dropped, leaving
    // payment rows behind for deleted orders.
    @Test
    void isNotMistakenForAMalformedOrderPlaced() {
        when(paymentService.purgeOrders(anyCollection())).thenReturn(Mono.empty());

        consumer().onOrderPlaced("""
                {"eventType":"OrdersPurged","username":"alice","orderIds":[7]}
                """);

        verify(paymentService).purgeOrders(List.of(7L));
        verify(paymentService, never()).processOrderPlaced(any(), any(), any());
    }

    @Test
    void ignoresAnEventWithNoOrderIds() {
        consumer().onOrderPlaced("""
                {"eventType":"OrdersPurged","username":"alice","orderIds":[]}
                """);

        verify(paymentService, never()).purgeOrders(anyCollection());
    }

    @Test
    void stillHandlesOrderPlacedNormally() {
        when(paymentService.processOrderPlaced(any(), any(), any())).thenReturn(Mono.empty());

        consumer().onOrderPlaced("""
                {"orderId":42,"username":"alice","total":10.00}
                """);

        verify(paymentService).processOrderPlaced(any(), any(), any());
        verify(paymentService, never()).purgeOrders(anyCollection());
    }

    @Test
    void stillHandlesRefundRequestedNormally() {
        when(paymentService.processRefundRequested(any())).thenReturn(Mono.empty());

        consumer().onOrderPlaced("""
                {"eventType":"RefundRequested","orderId":42,"username":"alice"}
                """);

        verify(paymentService).processRefundRequested(42L);
        verify(paymentService, never()).purgeOrders(anyCollection());
    }
}
