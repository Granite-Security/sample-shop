package org.granitesecurity.profile.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * One row per order already announced to admin. Written only through
 * {@code ProcessedOrderNoticeRepository.claim}, which is an insert-or-nothing —
 * this type exists to satisfy the repository's generics and to make the table
 * readable from a test.
 */
@Table("processed_order_notice")
@Getter
@Setter
public class ProcessedOrderNotice {

    @Id
    @Column("order_id")
    private Long orderId;

    @Column("processed_at")
    private Instant processedAt;
}
