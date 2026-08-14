package org.granitesecurity.delivery.service;

import org.granitesecurity.delivery.domain.Delivery;
import org.granitesecurity.delivery.repository.DeliveryEventRepository;
import org.granitesecurity.delivery.repository.DeliveryQueryRepository;
import org.granitesecurity.delivery.repository.DeliveryRepository;
import org.granitesecurity.delivery.repository.DeliveryTrackingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryServicePurgeTest {

    @Mock
    private DeliveryRepository deliveryRepository;

    @Mock
    private DeliveryTrackingRepository trackingRepository;

    @Mock
    private DeliveryEventRepository eventRepository;

    // Unused by the purge path; present because the constructor takes it.
    @Mock
    private DeliveryQueryRepository deliveryQueryRepository;

    private DeliveryService service() {
        return new DeliveryService(deliveryRepository, deliveryQueryRepository, trackingRepository,
                eventRepository, new ObjectMapper());
    }

    private Delivery delivery(UUID id, Long orderId) {
        Delivery delivery = new Delivery();
        delivery.setId(id);
        delivery.setOrderId(orderId);
        return delivery;
    }

    // delivery_tracking keys on delivery_id, not order_id — deleting the
    // deliveries alone would leave the tracking rows orphaned with nothing
    // pointing at them (§6).
    @Test
    void deletesTrackingRowsBeforeTheDeliveriesTheyPointAt() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(deliveryRepository.findByOrderIdIn(anyCollection()))
                .thenReturn(Flux.just(delivery(first, 1L), delivery(second, 2L)));
        when(trackingRepository.deleteByDeliveryIdIn(anyCollection())).thenReturn(Mono.just(4L));
        when(deliveryRepository.deleteByOrderIdIn(anyCollection())).thenReturn(Mono.just(2L));

        StepVerifier.create(service().purgeOrders(List.of(1L, 2L))).verifyComplete();

        verify(trackingRepository).deleteByDeliveryIdIn(List.of(first, second));
        verify(deliveryRepository).deleteByOrderIdIn(List.of(1L, 2L));
    }

    @Test
    void anEmptyOrderIdListTouchesNothing() {
        StepVerifier.create(service().purgeOrders(List.of())).verifyComplete();

        verifyNoInteractions(deliveryRepository);
        verifyNoInteractions(trackingRepository);
    }

    @Test
    void ordersWithNoDeliveryRowStillCompleteCleanly() {
        when(deliveryRepository.findByOrderIdIn(anyCollection())).thenReturn(Flux.empty());
        when(deliveryRepository.deleteByOrderIdIn(anyCollection())).thenReturn(Mono.just(0L));

        StepVerifier.create(service().purgeOrders(List.of(9L))).verifyComplete();

        verify(trackingRepository, never()).deleteByDeliveryIdIn(anyCollection());
    }

    // delivery_event is outbox plumbing, not user data (§6).
    @Test
    void leavesTheDeliveryEventOutboxAlone() {
        when(deliveryRepository.findByOrderIdIn(anyCollection())).thenReturn(Flux.empty());
        when(deliveryRepository.deleteByOrderIdIn(anyCollection())).thenReturn(Mono.just(0L));

        StepVerifier.create(service().purgeOrders(List.of(1L))).verifyComplete();

        verifyNoInteractions(eventRepository);
    }
}
