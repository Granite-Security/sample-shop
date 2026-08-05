package org.granitesecurity.balance.domain;

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
 * A record that a keyed operation already ran, and what it answered (D5).
 *
 * <p>Persistable because the id is a caller-supplied string, not generated: without
 * this, Spring Data sees a non-null id and issues an UPDATE that matches nothing.
 */
@Table("idempotency")
@Getter
@Setter
public class Idempotency implements Persistable<String> {

    @Id
    private String key;

    @Column("transfer_id")
    private UUID transferId;

    /** The original response, replayed verbatim on a retry. */
    private String response;

    @Column("created_at")
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    @Override
    public String getId() {
        return key;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public void markStored() {
        this.isNew = false;
    }
}
