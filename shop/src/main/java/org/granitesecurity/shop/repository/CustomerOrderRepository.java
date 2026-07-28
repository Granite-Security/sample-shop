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

    @Query("SELECT * FROM customer_order WHERE username = :username ORDER BY id DESC LIMIT :size OFFSET :offset")
    Flux<CustomerOrder> findByUsernamePaged(@Param("username") String username,
                                            @Param("size") int size,
                                            @Param("offset") long offset);

    @Query("SELECT * FROM customer_order ORDER BY id DESC LIMIT :size OFFSET :offset")
    Flux<CustomerOrder> findAllPaged(@Param("size") int size, @Param("offset") long offset);

    Mono<Long> deleteByIdIn(java.util.Collection<Long> ids);

    // Orphan sweep (docs/users/blocking-users.md §8 Phase 6): who owns orders,
    // so the caller can diff that against the users that actually exist.
    @Query("SELECT username, count(*) AS order_count FROM customer_order GROUP BY username")
    Flux<OrderOwner> findOrderOwners();

    @Query("SELECT id FROM customer_order WHERE id IN (:ids)")
    Flux<Long> findExistingIds(@Param("ids") java.util.Collection<Long> ids);

    record OrderOwner(String username, long orderCount) {}
}
