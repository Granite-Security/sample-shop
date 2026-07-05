package org.granitesecurity.delivery.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class DeliveryTracking {

    @Id
    private UUID id;

    @Column("delivery_id")
    private UUID deliveryId;

    private String status;

    private Instant timestamp;

    private String description;

    public DeliveryTracking() {}

    public DeliveryTracking(UUID deliveryId, String status, String description) {
        this.id = UUID.randomUUID();
        this.deliveryId = deliveryId;
        this.status = status;
        this.timestamp = Instant.now();
        this.description = description;
    }
}
