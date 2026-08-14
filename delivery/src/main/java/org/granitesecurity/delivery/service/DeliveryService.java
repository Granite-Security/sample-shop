package org.granitesecurity.delivery.service;

import tools.jackson.databind.ObjectMapper;
import org.granitesecurity.delivery.domain.Delivery;
import org.granitesecurity.delivery.domain.DeliveryEvent;
import org.granitesecurity.delivery.domain.DeliveryStatus;
import org.granitesecurity.delivery.domain.DeliveryTracking;
import org.granitesecurity.delivery.dto.DeliveryResponse;
import org.granitesecurity.delivery.dto.PagedResult;
import org.granitesecurity.delivery.dto.TrackingDetailResponse;
import org.granitesecurity.delivery.dto.TrackingDetailResponse.TrackingEvent;
import org.granitesecurity.delivery.repository.DeliveryEventRepository;
import org.granitesecurity.delivery.repository.DeliveryQueryRepository;
import org.granitesecurity.delivery.repository.DeliveryQueryRepository.SortKey;
import org.granitesecurity.delivery.repository.DeliveryRepository;
import org.granitesecurity.delivery.repository.DeliveryTrackingRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class DeliveryService {

    /** Page size ceiling — see {@link #getDeliveries}. */
    private static final int MAX_PAGE_SIZE = 100;

    private final DeliveryRepository deliveryRepository;
    private final DeliveryQueryRepository deliveryQueryRepository;
    private final DeliveryTrackingRepository trackingRepository;
    private final DeliveryEventRepository eventRepository;
    private final ObjectMapper objectMapper;

    public DeliveryService(DeliveryRepository deliveryRepository,
                           DeliveryQueryRepository deliveryQueryRepository,
                           DeliveryTrackingRepository trackingRepository,
                           DeliveryEventRepository eventRepository,
                           ObjectMapper objectMapper) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryQueryRepository = deliveryQueryRepository;
        this.trackingRepository = trackingRepository;
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Drops this service's rows for orders shop has purged. Keyed by order_id —
     * delivery has no username to match on (docs/users/blocking-users.md §6).
     *
     * <p>delivery_tracking keys on delivery_id, not order_id, so the delivery
     * ids have to be resolved first; deleting the deliveries alone would leave
     * the tracking rows orphaned.
     *
     * <p>Idempotent: deleting rows that are already gone is a no-op, so a
     * redelivered event needs no dedupe table. delivery_event is left alone —
     * it is outbox plumbing, not user data.
     */
    public Mono<Void> purgeOrders(java.util.Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Mono.empty();
        }
        return deliveryRepository.findByOrderIdIn(orderIds)
                .map(Delivery::getId)
                .collectList()
                .flatMap(deliveryIds -> deliveryIds.isEmpty()
                        ? Mono.just(0L)
                        : trackingRepository.deleteByDeliveryIdIn(deliveryIds))
                .then(deliveryRepository.deleteByOrderIdIn(orderIds))
                .then();
    }

    /** The order ids we hold delivery rows for — orphan sweep (§8 Phase 6). */
    public Mono<List<Long>> distinctOrderIds() {
        return deliveryRepository.findDistinctOrderIds().collectList();
    }

    public Mono<Delivery> createDelivery(Long orderId, String recipientName, String addressLine1,
                                          String addressLine2, String city, String state,
                                          String zipCode, String country, String items) {
        Delivery delivery = new Delivery(orderId, recipientName, addressLine1, addressLine2,
                city, state, zipCode, country, items);
        return deliveryRepository.save(delivery)
                .flatMap(saved -> emitEvent(saved, "DELIVERY_CREATED", Map.of("orderId", orderId))
                        .thenReturn(saved));
    }

    public Mono<DeliveryResponse> getDeliveryByOrderId(Long orderId) {
        return deliveryRepository.findByOrderId(orderId)
                .map(this::toResponse);
    }

    /**
     * One page of shipments, filtered and sorted in Postgres.
     *
     * <p>Every filter and sort the back offices offer is applied here rather than in
     * the browser. That is not a refactor for its own sake: filtering a page in the
     * client filters only the rows that page happens to hold, which reads as data
     * silently going missing. The two are a single change, and neither half is
     * correct alone.
     *
     * <p>{@code size} is clamped to {@value #MAX_PAGE_SIZE}. Without a ceiling
     * {@code ?size=1000000} restores exactly the unbounded fetch pagination replaced,
     * and does it on request from outside.
     */
    public Mono<PagedResult<DeliveryResponse>> getDeliveries(String status, String paymentStatus,
                                                             Instant from, Instant to,
                                                             String sort, boolean ascending,
                                                             int page, int size) {
        int limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageNumber = Math.max(page, 0);
        long offset = (long) pageNumber * limit;
        SortKey sortKey = SortKey.from(sort);

        Mono<Long> total = deliveryQueryRepository.count(status, paymentStatus, from, to);
        Mono<List<DeliveryResponse>> items = deliveryQueryRepository
                .findPage(status, paymentStatus, from, to, sortKey, ascending, limit, offset)
                .map(this::toResponse)
                .collectList();
        return total.zipWith(items)
                .map(t -> new PagedResult<>(t.getT2(), t.getT1(), pageNumber, limit));
    }

    public Mono<TrackingDetailResponse> getTrackingDetail(Long orderId) {
        return deliveryRepository.findByOrderId(orderId)
                .flatMap(delivery -> trackingRepository.findByDeliveryIdOrderByTimestampDesc(delivery.getId())
                        .map(t -> new TrackingEvent(t.getStatus(), t.getTimestamp(), t.getDescription()))
                        .collectList()
                        .map(events -> new TrackingDetailResponse(
                                delivery.getId().toString(),
                                delivery.getOrderId(),
                                delivery.getStatus(),
                                delivery.getPaymentStatus(),
                                delivery.getItems(),
                                delivery.getEstimatedDeliveryDate(),
                                events
                        )));
    }

    public Mono<DeliveryResponse> updateStatus(Long orderId, String status, String description) {
        return deliveryRepository.findByOrderId(orderId)
                .flatMap(delivery -> {
                    String current = delivery.getStatus();
                    if (!isValidTransition(current, status)) {
                        return Mono.error(new IllegalArgumentException(
                                "Invalid transition from " + current + " to " + status));
                    }
                    delivery.setStatus(status);
                    delivery.setUpdatedAt(java.time.Instant.now());
                    delivery.markNotNew();
                    DeliveryTracking tracking = new DeliveryTracking(delivery.getId(), status,
                            description != null ? description : status);
                    return trackingRepository.save(tracking)
                            .then(deliveryRepository.save(delivery));
                })
                .flatMap(saved -> emitEvent(saved, "STATUS_UPDATE",
                        Map.of("status", status, "description", description))
                        .thenReturn(toResponse(saved)));
    }

    public Mono<Delivery> updatePaymentStatus(Long orderId, String paymentStatus) {
        return deliveryRepository.findByOrderId(orderId)
                .flatMap(delivery -> {
                    delivery.setPaymentStatus(paymentStatus);
                    delivery.setUpdatedAt(java.time.Instant.now());
                    delivery.markNotNew();
                    return deliveryRepository.save(delivery);
                });
    }

    private DeliveryResponse toResponse(Delivery delivery) {
        return new DeliveryResponse(
                delivery.getId().toString(),
                delivery.getOrderId(),
                delivery.getStatus(),
                delivery.getPaymentStatus(),
                delivery.getItems(),
                delivery.getRecipientName(),
                delivery.getAddressLine1(),
                delivery.getAddressLine2(),
                delivery.getCity(),
                delivery.getState(),
                delivery.getZipCode(),
                delivery.getCountry(),
                delivery.getEstimatedDeliveryDate(),
                delivery.getCreatedAt(),
                delivery.getUpdatedAt()
        );
    }

    private boolean isValidTransition(String current, String next) {
        return switch (current) {
            case "PENDING" -> List.of("DISPATCHED", "FAILED").contains(next);
            case "DISPATCHED" -> List.of("DELIVERED", "FAILED").contains(next);
            default -> false;
        };
    }

    private Mono<DeliveryEvent> emitEvent(Delivery delivery, String eventType, Map<String, Object> details) {
        try {
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("deliveryId", delivery.getId().toString());
            payload.put("orderId", delivery.getOrderId());
            payload.put("status", delivery.getStatus());
            payload.put("eventType", eventType);
            payload.putAll(details);
            String json = objectMapper.writeValueAsString(payload);
            DeliveryEvent event = new DeliveryEvent("DELIVERY", delivery.getId().toString(), eventType, json, "PENDING");
            return eventRepository.save(event);
        } catch (Exception e) {
            return Mono.error(e);
        }
    }
}
