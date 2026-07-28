package org.granitesecurity.notification.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("notification_log")
@Getter
@Setter
public class NotificationLog {

    @Id
    private Long id;

    @Column("event_id")
    private UUID eventId;

    @Column("event_type")
    private String eventType;

    private String channel;

    private String recipient;

    private String status;

    @Column("provider_message_id")
    private String providerMessageId;

    private String error;

    @Column("created_at")
    private Instant createdAt;

    public NotificationLog() {}

    public NotificationLog(UUID eventId, String eventType, String channel, String recipient,
                           String status, String providerMessageId, String error) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.channel = channel;
        this.recipient = recipient;
        this.status = status;
        this.providerMessageId = providerMessageId;
        this.error = truncate(error);
        this.createdAt = Instant.now();
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= 2000) {
            return value;
        }
        return value.substring(0, 2000);
    }
}
