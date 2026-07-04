package org.granitesecurity.payment.repository;

import org.granitesecurity.payment.domain.StripeEvent;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface StripeEventRepository extends ReactiveCrudRepository<StripeEvent, UUID> {

    Mono<StripeEvent> findByStripeEventId(String stripeEventId);
}
