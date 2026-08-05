package org.granitesecurity.profile.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * One message from one user to another (docs/users/messaging.md §4).
 *
 * <p>Participants are usernames, not foreign keys — matching delivery_address,
 * admin_action and user_file, and keeping the row readable after either party
 * is deleted.
 */
@Table("user_message")
@Getter
@Setter
public class UserMessage {

    @Id
    private Long id;

    @Column("sender_username")
    private String senderUsername;

    @Column("recipient_username")
    private String recipientUsername;

    /** Optional. Blank input is normalised to null so "absent" has one representation. */
    private String subject;

    private String body;

    /** Null means unread. Only the recipient can set it. */
    @Column("read_at")
    private Instant readAt;

    // Deleting from your own inbox must not remove the message from the other
    // party's Sent folder, so each side has its own flag and the row survives
    // until both are set.
    @Column("sender_deleted")
    private boolean senderDeleted;

    @Column("recipient_deleted")
    private boolean recipientDeleted;

    @Column("created_at")
    private Instant createdAt;
}
