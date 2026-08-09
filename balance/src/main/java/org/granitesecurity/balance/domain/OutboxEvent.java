package org.granitesecurity.balance.domain;

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
 * One announcement of one movement, written in the same transaction as the ledger
 * rows that caused it (docs/finance/accounting.md §4.2).
 *
 * <p>Copied from payment's, deliberately, down to the {@link Persistable} trick: the
 * id is assigned in Java rather than by the database, so Spring Data would otherwise
 * see a non-null id, assume the row exists and issue an UPDATE that touches nothing.
 * {@code isNew} is what makes the first save an INSERT.
 */
@Table("outbox")
@Getter
@Setter
public class OutboxEvent implements Persistable<UUID> {

    /** Everything balance publishes goes to one topic, keyed by username. */
    public static final String TOPIC = "balance.events";

    public static final String AGGREGATE_TYPE = "balance";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";

    @Id
    private UUID id;

    @Column("aggregate_type")
    private String aggregateType;

    /**
     * The username this movement is about, and the Kafka message key.
     *
     * <p>Keying by username is what keeps one user's facts in one partition and
     * therefore in order. A {@code Refunded} overtaking the {@code Spent} it reverses
     * would leave the books trying to reverse a sale they have not booked.
     */
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

    public OutboxEvent() {}

    public OutboxEvent(String aggregateId, String eventType, String payload) {
        this.id = UUID.randomUUID();
        this.aggregateType = AGGREGATE_TYPE;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = STATUS_PENDING;
        this.createdAt = Instant.now();
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
