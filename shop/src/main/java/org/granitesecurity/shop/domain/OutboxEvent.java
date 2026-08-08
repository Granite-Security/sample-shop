package org.granitesecurity.shop.domain;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("outbox")
public class OutboxEvent implements Persistable<UUID> {

    /** Where a row goes when it does not say. Matches the column's DEFAULT. */
    public static final String DEFAULT_TOPIC = "orders.events";

    @Id
    @Getter
    @Setter
    private UUID id;

    @Column("aggregate_type")
    @Getter
    @Setter
    private String aggregateType;

    @Column("aggregate_id")
    @Getter
    @Setter
    private String aggregateId;

    @Column("event_type")
    @Getter
    @Setter
    private String eventType;

    @Getter
    @Setter
    private String payload;

    @Getter
    @Setter
    private String status;

    @Getter
    @Setter
    private String topic;

    @Column("created_at")
    @Getter
    @Setter
    private Instant createdAt;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Transient
    private boolean isNew = true;

    public OutboxEvent() {}

    public OutboxEvent(String aggregateType, String aggregateId, String eventType, String payload, String status) {
        this(aggregateType, aggregateId, eventType, payload, status, DEFAULT_TOPIC);
    }

    public OutboxEvent(String aggregateType, String aggregateId, String eventType, String payload, String status,
                       String topic) {
        this.id = UUID.randomUUID();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = status;
        this.topic = topic;
        this.createdAt = Instant.now();
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
