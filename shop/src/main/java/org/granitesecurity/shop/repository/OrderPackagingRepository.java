package org.granitesecurity.shop.repository;

import org.granitesecurity.shop.domain.OrderPackaging;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface OrderPackagingRepository extends ReactiveCrudRepository<OrderPackaging, Long> {
    Flux<OrderPackaging> findByOrderId(Long orderId);
}
