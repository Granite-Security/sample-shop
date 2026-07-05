package org.granitesecurity.delivery.repository;

import org.granitesecurity.delivery.domain.DeliveryEvent;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface DeliveryEventRepository extends R2dbcRepository<DeliveryEvent, UUID> {
    Flux<DeliveryEvent> findByStatus(String status);

    @Modifying
    @Query("UPDATE delivery_event SET status = 'PUBLISHED' WHERE id = :id")
    Mono<Integer> markPublished(UUID id);
}
