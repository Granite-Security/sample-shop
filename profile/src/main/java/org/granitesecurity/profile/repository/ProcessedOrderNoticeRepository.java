package org.granitesecurity.profile.repository;

import org.granitesecurity.profile.domain.ProcessedOrderNotice;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface ProcessedOrderNoticeRepository extends ReactiveCrudRepository<ProcessedOrderNotice, Long> {

    /**
     * Claims the right to notify about this order, returning 1 the first time and 0 on
     * every redelivery.
     *
     * <p>ON CONFLICT DO NOTHING rather than a SELECT-then-INSERT: two consumer threads
     * handling a redelivered record at the same moment both pass a read check, and only
     * one can win a primary key. The database is the lock.
     */
    @Modifying
    @Query("INSERT INTO processed_order_notice (order_id) VALUES (:orderId) ON CONFLICT DO NOTHING")
    Mono<Long> claim(Long orderId);
}
