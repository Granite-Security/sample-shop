package org.granitesecurity.payment.repository;

import org.granitesecurity.payment.domain.PaymentAttempt;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.UUID;

@Repository
public interface PaymentAttemptRepository extends ReactiveCrudRepository<PaymentAttempt, UUID> {

    Flux<PaymentAttempt> findByOrderIdOrderByCreatedAtDesc(Long orderId);

    /** What was actually captured for this order, if anything was. */
    Mono<PaymentAttempt> findByOrderIdAndStatus(Long orderId, String status);

    Mono<Long> deleteByOrderIdIn(Collection<Long> orderIds);
}
