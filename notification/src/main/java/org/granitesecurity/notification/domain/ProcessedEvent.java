package org.granitesecurity.notification.domain;

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

/**
 * Dedupe marker, inserted before a send is attempted. Implements Persistable so
 * Spring Data issues an INSERT rather than an UPDATE for the caller-supplied id —
 * the insert failing with a duplicate key is exactly the signal we want.
 */
@Table("processed_event")
public class ProcessedEvent implements Persistable<UUID> {

    @Id
    @Getter
    @Setter
    private UUID eventId;

    @Column("event_type")
    @Getter
    @Setter
    private String eventType;

    @Column("processed_at")
    @Getter
    @Setter
    private Instant processedAt;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Transient
    private boolean isNew = true;

    public ProcessedEvent() {}

    public ProcessedEvent(UUID eventId, String eventType) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
