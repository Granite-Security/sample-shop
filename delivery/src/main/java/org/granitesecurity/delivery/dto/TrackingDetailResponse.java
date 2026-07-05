package org.granitesecurity.delivery.dto;

import java.time.Instant;
import java.util.List;

public record TrackingDetailResponse(
    String deliveryId,
    Long orderId,
    String currentStatus,
    String paymentStatus,
    String items,
    Instant estimatedDelivery,
    List<TrackingEvent> events
) {
    public record TrackingEvent(
        String status,
        Instant timestamp,
        String description
    ) {}
}
