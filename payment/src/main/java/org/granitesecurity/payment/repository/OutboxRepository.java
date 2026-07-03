package org.granitesecurity.payment.repository;

import org.granitesecurity.payment.domain.OutboxEvent;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface OutboxRepository extends ReactiveCrudRepository<OutboxEvent, UUID> {

    Flux<OutboxEvent> findByStatusOrderByCreatedAtAsc(String status);

    @Query("UPDATE outbox SET status = 'SENT' WHERE id = :id")
    Mono<Integer> markSent(UUID id);
}
