package org.granitesecurity.payment.domain;

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
 * The webhook dedupe log: one row per event we have already acted on.
 *
 * <p>Keyed by {@code (provider, provider_event_id)} rather than the event id alone —
 * two providers can legitimately issue the same id string, and collapsing them would
 * let one provider's event silently suppress the other's.
 */
@Table("provider_event")
@Getter
@Setter
public class ProviderEvent implements Persistable<UUID> {

    @Id
    private UUID id;

    private String provider;

    @Column("provider_event_id")
    private String providerEventId;

    private String type;

    @Column("created_at")
    private Instant createdAt;

    @Column("processed_at")
    private Instant processedAt;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Transient
    private boolean isNew = true;

    public ProviderEvent() {}

    public ProviderEvent(String provider, String providerEventId, String type) {
        this.id = UUID.randomUUID();
        this.provider = provider;
        this.providerEventId = providerEventId;
        this.type = type;
        this.createdAt = Instant.now();
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
