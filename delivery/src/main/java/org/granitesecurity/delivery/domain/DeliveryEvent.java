package org.granitesecurity.delivery.domain;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class DeliveryEvent implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column("aggregate_type")
    private String aggregateType;

    @Column("aggregate_id")
    private String aggregateId;

    @Column("event_type")
    private String eventType;

    private String payload;

    private String status;

    @Column("created_at")
    private Instant createdAt;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Transient
    private boolean isNew = true;

    public DeliveryEvent() {}

    public DeliveryEvent(String aggregateType, String aggregateId, String eventType, String payload, String status) {
        this.id = UUID.randomUUID();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = status;
        this.createdAt = Instant.now();
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
