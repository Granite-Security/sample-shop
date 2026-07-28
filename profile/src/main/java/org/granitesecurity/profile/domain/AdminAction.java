package org.granitesecurity.profile.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("admin_action")
@Getter
@Setter
public class AdminAction {

    @Id
    private Long id;

    private String actor;

    private String action;

    @Column("target_user")
    private String targetUser;

    private String outcome;

    @Column("order_count")
    private Integer orderCount;

    private String reason;

    @Column("created_at")
    private Instant createdAt;

    public AdminAction() {}

    public AdminAction(String actor, String action, String targetUser, String outcome,
                       Integer orderCount, String reason) {
        this.actor = actor;
        this.action = action;
        this.targetUser = targetUser;
        this.outcome = outcome;
        this.orderCount = orderCount;
        this.reason = reason;
        this.createdAt = Instant.now();
    }
}
