package org.granitesecurity.accounting.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One accounting period, {@code YYYY-MM} (docs/finance/accounting.md §6).
 *
 * <p>Closing is the thing a report computed on demand can never do. A closed period is
 * frozen: a fact that arrives afterwards posts to the open period as a prior-period
 * adjustment, rather than quietly changing a month someone has already read and acted on.
 */
@Table("period")
@Getter
@Setter
public class Period implements Persistable<String> {

    public static final String OPEN = "OPEN";
    public static final String CLOSED = "CLOSED";

    /** The natural key is the period itself; a surrogate id would let two rows claim one month. */
    @Id
    private String code;

    @Column("starts_on")
    private LocalDate startsOn;

    /** Exclusive. */
    @Column("ends_on")
    private LocalDate endsOn;

    private String status = OPEN;

    @Column("closed_at")
    private Instant closedAt;

    @Column("closed_by")
    private String closedBy;

    @Transient
    private boolean isNew;

    /**
     * Persistable keys on getId(), and this table's id is its code — the natural key, so
     * that two rows cannot claim one month.
     */
    @Override
    public String getId() {
        return code;
    }

    public boolean isClosed() {
        return CLOSED.equals(status);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    /** Assigned keys mean Spring Data would otherwise UPDATE a row that does not exist yet. */
    public Period markNew() {
        this.isNew = true;
        return this;
    }
}
