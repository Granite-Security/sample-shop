package org.granitesecurity.payment.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("stripe_event")
@Getter
@Setter
public class StripeEvent {

    @Id
    private UUID id;

    @Column("stripe_event_id")
    private String stripeEventId;

    private String type;

    @Column("created_at")
    private Instant createdAt;

    @Column("processed_at")
    private Instant processedAt;

    public StripeEvent() {}

    public StripeEvent(String stripeEventId, String type) {
        this.id = UUID.randomUUID();
        this.stripeEventId = stripeEventId;
        this.type = type;
        this.createdAt = Instant.now();
    }
}
