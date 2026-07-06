package org.granitesecurity.delivery.service;

import tools.jackson.databind.ObjectMapper;
import org.granitesecurity.delivery.domain.Delivery;
import org.granitesecurity.delivery.domain.DeliveryEvent;
import org.granitesecurity.delivery.domain.DeliveryStatus;
import org.granitesecurity.delivery.domain.DeliveryTracking;
import org.granitesecurity.delivery.dto.DeliveryResponse;
import org.granitesecurity.delivery.dto.TrackingDetailResponse;
import org.granitesecurity.delivery.dto.TrackingDetailResponse.TrackingEvent;
import org.granitesecurity.delivery.repository.DeliveryEventRepository;
import org.granitesecurity.delivery.repository.DeliveryRepository;
import org.granitesecurity.delivery.repository.DeliveryTrackingRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DeliveryService {

    private final DeliveryRepository deliveryRepository;
    private final DeliveryTrackingRepository trackingRepository;
    private final DeliveryEventRepository eventRepository;
    private final ObjectMapper objectMapper;

    public DeliveryService(DeliveryRepository deliveryRepository,
                           DeliveryTrackingRepository trackingRepository,
                           DeliveryEventRepository eventRepository,
                           ObjectMapper objectMapper) {
        this.deliveryRepository = deliveryRepository;
        this.trackingRepository = trackingRepository;
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
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
                .flatMap(this::toResponse);
    }

    public Flux<DeliveryResponse> getDeliveries(String status, String paymentStatus) {
        Flux<Delivery> stream;
        if (status != null && paymentStatus != null) {
            stream = deliveryRepository.findByStatusAndPaymentStatus(status, paymentStatus);
        } else if (status != null) {
            stream = deliveryRepository.findByStatus(status);
        } else if (paymentStatus != null) {
            stream = deliveryRepository.findByPaymentStatus(paymentStatus);
        } else {
            stream = deliveryRepository.findAll();
        }
        return stream.flatMap(this::toResponse);
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
                        .then(toResponse(saved)));
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

    public Mono<DeliveryResponse> toResponse(Delivery delivery) {
        return trackingRepository.findByDeliveryIdOrderByTimestampDesc(delivery.getId())
                .map(t -> new TrackingEvent(
                        t.getStatus(),
                        t.getTimestamp(),
                        t.getDescription()
                ))
                .collectList()
                .map(events -> new DeliveryResponse(
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
                        delivery.getUpdatedAt(),
                        events
                ));
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
