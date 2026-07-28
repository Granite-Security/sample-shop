package org.granitesecurity.payment.repository;

import org.granitesecurity.payment.domain.Payment;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.UUID;

@Repository
public interface PaymentRepository extends ReactiveCrudRepository<Payment, UUID> {

    Mono<Payment> findByOrderId(Long orderId);

    Flux<Payment> findByOrderIdIn(Collection<Long> orderIds);

    Mono<Long> deleteByOrderIdIn(Collection<Long> orderIds);

    // Orphan sweep (docs/users/blocking-users.md §8 Phase 6): the order ids we
    // hold rows for. Only shop can say which of them no longer exist.
    @org.springframework.data.r2dbc.repository.Query("SELECT DISTINCT order_id FROM payment")
    Flux<Long> findDistinctOrderIds();
}
