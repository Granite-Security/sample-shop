package org.granitesecurity.shop.repository;

import org.granitesecurity.shop.domain.OutboxEvent;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface OutboxRepository extends ReactiveCrudRepository<OutboxEvent, UUID> {

    Flux<OutboxEvent> findByStatusOrderByCreatedAtAsc(String status);

    @Modifying
    @Query("UPDATE outbox SET status = 'SENT' WHERE id = :id")
    Mono<Integer> markSent(@Param("id") UUID id);
}
