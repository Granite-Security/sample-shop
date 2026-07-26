package org.granitesecurity.payment.repository;

import org.granitesecurity.payment.domain.Refund;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface RefundRepository extends ReactiveCrudRepository<Refund, UUID> {

    Mono<Refund> findByOrderId(Long orderId);
}
