package org.granitesecurity.delivery.repository;

import org.granitesecurity.delivery.domain.Delivery;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface DeliveryRepository extends R2dbcRepository<Delivery, UUID> {
    Mono<Delivery> findByOrderId(Long orderId);
    Flux<Delivery> findByStatus(String status);
    Flux<Delivery> findByPaymentStatus(String paymentStatus);
    Flux<Delivery> findByStatusAndPaymentStatus(String status, String paymentStatus);
}
