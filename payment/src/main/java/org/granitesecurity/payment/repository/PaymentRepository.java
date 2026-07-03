package org.granitesecurity.payment.repository;

import org.granitesecurity.payment.domain.Payment;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface PaymentRepository extends ReactiveCrudRepository<Payment, UUID> {

    Mono<Payment> findByOrderId(Long orderId);
}
