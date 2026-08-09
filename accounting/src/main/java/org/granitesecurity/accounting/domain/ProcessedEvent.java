package org.granitesecurity.accounting.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * Idempotency (docs/finance/accounting.md §6). Kafka is at-least-once and this is money:
 * replaying an event must produce one journal, not two.
 *
 * <p>Copied from notification's, and explicitly <b>not</b> its staleness rule (D23).
 * notification drops events past a per-type age as DROPPED_STALE, which is right for
 * email — nobody wants a week-old password reset — and exactly wrong here. A late fact
 * is still a fact and must be booked, into the open period if its own has closed.
 */
@Table("processed_event")
@Getter
@Setter
public class ProcessedEvent implements Persistable<String> {

    @Id
    @Column("event_key")
    private String eventKey;

    private String topic;

    @Column("event_type")
    private String eventType;

    @Column("processed_at")
    private Instant processedAt;

    @Transient
    private boolean isNew = true;

    @Override
    public String getId() {
        return eventKey;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
