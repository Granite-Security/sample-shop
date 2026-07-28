package org.granitesecurity.delivery.consumer;

import org.granitesecurity.delivery.service.DeliveryService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderEventConsumerPurgeTest {

    @Mock
    private DeliveryService deliveryService;

    private OrderEventConsumer consumer() {
        return new OrderEventConsumer(deliveryService);
    }

    @Test
    void purgesTheOrderIdsCarriedByTheEvent() {
        when(deliveryService.purgeOrders(anyCollection())).thenReturn(Mono.empty());

        consumer().consume("""
                {"eventType":"OrdersPurged","username":"alice","orderIds":[1,2]}
                """);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(deliveryService).purgeOrders(captor.capture());
        assertThat(captor.getValue()).containsExactly(1L, 2L);
    }

    // OrdersPurged carries no "address", so on the OrderPlaced path it would be
    // dropped as malformed and delivery rows would survive their orders.
    @Test
    void isNotMistakenForAnOrderPlacedMissingItsAddress() {
        when(deliveryService.purgeOrders(anyCollection())).thenReturn(Mono.empty());

        consumer().consume("""
                {"eventType":"OrdersPurged","username":"alice","orderIds":[7]}
                """);

        verify(deliveryService).purgeOrders(List.of(7L));
        verify(deliveryService, never()).createDelivery(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void ignoresAnEventWithNoOrderIds() {
        consumer().consume("""
                {"eventType":"OrdersPurged","orderIds":[]}
                """);

        verify(deliveryService, never()).purgeOrders(anyCollection());
    }

    @Test
    void stillHandlesOrderPlacedNormally() {
        when(deliveryService.createDelivery(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());

        consumer().consume("""
                {"orderId":42,"username":"alice","items":[],
                 "address":{"recipientName":"Alice","addressLine1":"Line 1","addressLine2":"",
                            "city":"Town","state":"","zipCode":"1234","country":"NL"}}
                """);

        verify(deliveryService).createDelivery(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(deliveryService, never()).purgeOrders(anyCollection());
    }
}
