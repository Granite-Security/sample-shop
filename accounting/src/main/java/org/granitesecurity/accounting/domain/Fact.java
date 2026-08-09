package org.granitesecurity.accounting.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A domain event as it arrived, stored before any journal is derived from it
 * (docs/finance/accounting.md §6).
 *
 * <p>Out-of-order delivery is guaranteed, not unlikely: Kafka orders within a partition
 * and these are four topics, so a payment can arrive before the order it pays for. So:
 * store the fact, then derive the journal. A fact whose prerequisites have not landed
 * stays {@code UNPOSTED} and is retried, instead of crashing the consumer or posting
 * half a movement.
 */
@Table("fact")
@Getter
@Setter
public class Fact {

    public static final String UNPOSTED = "UNPOSTED";
    public static final String POSTED = "POSTED";
    /** Correctly produces no journal — a gift issuance, a failed payment, a foreign currency. */
    public static final String IGNORED = "IGNORED";

    @Id
    private Long id;

    private String topic;

    @Column("event_type")
    private String eventType;

    /** Unique. The producer's own identity for this event, prefixed by topic. */
    @Column("event_key")
    private String eventKey;

    /** The order id, mostly: the key facts about one sale are joined by. */
    @Column("aggregate_id")
    private String aggregateId;

    @Column("occurred_at")
    private Instant occurredAt;

    @Column("received_at")
    private Instant receivedAt;

    private String payload;

    private String status;

    @Column("journal_id")
    private UUID journalId;

    private int attempts;

    @Column("last_error")
    private String lastError;
}
