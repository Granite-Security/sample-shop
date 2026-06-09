package org.granitesecurity.shop.repository;

import org.granitesecurity.shop.domain.CustomerOrder;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface CustomerOrderRepository extends ReactiveCrudRepository<CustomerOrder, Long> {
    Flux<CustomerOrder> findByUsername(String username);
}
