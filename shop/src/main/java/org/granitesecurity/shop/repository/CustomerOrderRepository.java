package org.granitesecurity.shop.repository;

import org.granitesecurity.shop.domain.CustomerOrder;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface CustomerOrderRepository extends ReactiveCrudRepository<CustomerOrder, Long> {
    Flux<CustomerOrder> findByUsername(String username);
    Mono<Long> countByUsername(String username);

    @Query("SELECT * FROM customer_order WHERE username = :username ORDER BY id LIMIT :size OFFSET :offset")
    Flux<CustomerOrder> findByUsernamePaged(@Param("username") String username,
                                            @Param("size") int size,
                                            @Param("offset") long offset);
}
