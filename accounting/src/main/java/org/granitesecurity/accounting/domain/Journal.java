package org.granitesecurity.accounting.domain;

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
 * One journal entry: a fact with a posting date (docs/finance/accounting.md §1.1).
 *
 * <p>Written once and never amended — the database enforces that, not a convention.
 * A wrong entry is corrected by a reversing entry that points at it.
 */
@Table("journal")
@Getter
@Setter
public class Journal implements Persistable<UUID> {

    public static final String SOURCE_EVENT = "EVENT";
    public static final String SOURCE_SCHEDULE = "SCHEDULE";
    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_OPENING = "OPENING";

    @Id
    private UUID id;

    @Column("period_code")
    private String periodCode;

    /**
     * The business date this entry belongs to — the producer's, not ours. Bucketing by
     * when the consumer happened to run is how a rebalanced consumer group rewrites
     * the books (§6).
     */
    @Column("occurred_at")
    private Instant occurredAt;

    @Column("posted_at")
    private Instant postedAt;

    private String source;

    @Column("event_type")
    private String eventType;

    private String reference;

    private String memo;

    @Column("created_by")
    private String createdBy;

    @Column("reverses_id")
    private UUID reversesId;

    /** The fact was dated inside a closed period and was booked into the open one. */
    @Column("prior_period")
    private boolean priorPeriod;

    private boolean estimated;

    /** The assumption set and its asOf, so an estimate can never render as a measurement (D21). */
    private String assumptions;

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }
}
