package org.granitesecurity.delivery.service;

import tools.jackson.databind.ObjectMapper;
import org.granitesecurity.delivery.domain.Delivery;
import org.granitesecurity.delivery.repository.DeliveryEventRepository;
import org.granitesecurity.delivery.repository.DeliveryQueryRepository;
import org.granitesecurity.delivery.repository.DeliveryQueryRepository.SortKey;
import org.granitesecurity.delivery.repository.DeliveryRepository;
import org.granitesecurity.delivery.repository.DeliveryTrackingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The paging arithmetic and the parameter allow-lists — the parts that decide what
 * SQL is issued, and the parts a caller can push on from outside.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryPagingTest {

    @Mock
    private DeliveryRepository deliveryRepository;

    @Mock
    private DeliveryQueryRepository deliveryQueryRepository;

    @Mock
    private DeliveryTrackingRepository trackingRepository;

    @Mock
    private DeliveryEventRepository eventRepository;

    private DeliveryService service() {
        when(deliveryQueryRepository.findPage(any(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyLong()))
                .thenReturn(Flux.just(delivery()));
        when(deliveryQueryRepository.count(any(), any(), any(), any()))
                .thenReturn(Mono.just(97L));
        return new DeliveryService(deliveryRepository, deliveryQueryRepository, trackingRepository,
                eventRepository, new ObjectMapper());
    }

    private Delivery delivery() {
        Delivery delivery = new Delivery();
        delivery.setId(UUID.randomUUID());
        delivery.setOrderId(7L);
        delivery.setStatus("PENDING");
        delivery.setPaymentStatus("UNPAID");
        delivery.setCreatedAt(Instant.now());
        delivery.setUpdatedAt(Instant.now());
        return delivery;
    }

    /**
     * The ceiling is what keeps pagination from being opt-out: without it
     * {@code ?size=1000000} fetches the table, which is the behaviour paging replaced.
     */
    @Test
    void clampsPageSizeToTheCeiling() {
        DeliveryService service = service();

        StepVerifier.create(service.getDeliveries(null, null, null, null, "orderId", false, 0, 1_000_000))
                .assertNext(result -> assertThat(result.size()).isEqualTo(100))
                .verifyComplete();

        verify(deliveryQueryRepository).findPage(any(), any(), any(), any(), any(), anyBoolean(), eq(100), eq(0L));
    }

    @Test
    void clampsNonPositiveSizeAndNegativePageToTheFirstRow() {
        DeliveryService service = service();

        StepVerifier.create(service.getDeliveries(null, null, null, null, "orderId", false, -5, 0))
                .assertNext(result -> {
                    assertThat(result.size()).isEqualTo(1);
                    assertThat(result.page()).isZero();
                })
                .verifyComplete();

        verify(deliveryQueryRepository).findPage(any(), any(), any(), any(), any(), anyBoolean(), eq(1), eq(0L));
    }

    /** Offset is page times the clamped size, not the requested one. */
    @Test
    void offsetFollowsTheClampedSize() {
        DeliveryService service = service();

        StepVerifier.create(service.getDeliveries(null, null, null, null, "createdAt", true, 3, 500))
                .expectNextCount(1)
                .verifyComplete();

        verify(deliveryQueryRepository).findPage(any(), any(), any(), any(),
                eq(SortKey.CREATED_AT), eq(true), eq(100), eq(300L));
    }

    /**
     * An unknown sort key is the one input that would otherwise reach {@code ORDER BY}
     * as a column name, so it falls back rather than being passed through.
     */
    @Test
    void unknownSortKeyFallsBackToOrderId() {
        DeliveryService service = service();

        StepVerifier.create(service.getDeliveries(null, null, null, null,
                        "created_at; DROP TABLE delivery", false, 0, 20))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<SortKey> sortKey = ArgumentCaptor.forClass(SortKey.class);
        verify(deliveryQueryRepository).findPage(any(), any(), any(), any(),
                sortKey.capture(), anyBoolean(), anyInt(), anyLong());
        assertThat(sortKey.getValue()).isEqualTo(SortKey.ORDER_ID);
    }

    /** Sort keys are matched without regard to case, so `createdat` still sorts by date. */
    @Test
    void sortKeyMatchIsCaseInsensitive() {
        assertThat(SortKey.from("CREATEDAT")).isEqualTo(SortKey.CREATED_AT);
        assertThat(SortKey.from("createdAt")).isEqualTo(SortKey.CREATED_AT);
        assertThat(SortKey.from(null)).isEqualTo(SortKey.ORDER_ID);
    }

    /**
     * The total counts every matching row, not the page — a pager that reported the
     * page length would always show one page.
     */
    @Test
    void totalCountsEveryMatchingRowNotThePage() {
        DeliveryService service = service();

        StepVerifier.create(service.getDeliveries("PENDING", null, null, null, "orderId", false, 0, 20))
                .assertNext(result -> {
                    assertThat(result.total()).isEqualTo(97L);
                    assertThat(result.items()).hasSize(1);
                })
                .verifyComplete();

        verify(deliveryQueryRepository).count(eq("PENDING"), any(), any(), any());
    }
}
