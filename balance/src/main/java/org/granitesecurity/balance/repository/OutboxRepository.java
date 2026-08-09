package org.granitesecurity.balance.repository;

import org.granitesecurity.balance.domain.OutboxEvent;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface OutboxRepository extends ReactiveCrudRepository<OutboxEvent, UUID> {

    /**
     * Oldest first, so a user's facts reach Kafka in the order they happened. The
     * relay publishes them one at a time for the same reason.
     */
    @Query("SELECT * FROM outbox WHERE status = 'PENDING' ORDER BY created_at, id LIMIT :limit")
    Flux<OutboxEvent> findPending(int limit);

    @Modifying
    @Query("UPDATE outbox SET status = 'SENT' WHERE id = :id")
    Mono<Integer> markSent(UUID id);
}
