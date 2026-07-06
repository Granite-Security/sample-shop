package org.granitesecurity.delivery.dto;

import java.time.Instant;
import java.util.List;

import org.granitesecurity.delivery.dto.TrackingDetailResponse.TrackingEvent;

public record DeliveryResponse(
    String id,
    Long orderId,
    String status,
    String paymentStatus,
    String items,
    String recipientName,
    String addressLine1,
    String addressLine2,
    String city,
    String state,
    String zipCode,
    String country,
    Instant estimatedDeliveryDate,
    Instant createdAt,
    Instant updatedAt,
    List<TrackingEvent> events
) {}
