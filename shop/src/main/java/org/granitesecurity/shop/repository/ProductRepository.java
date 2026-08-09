package org.granitesecurity.shop.repository;

import org.granitesecurity.shop.domain.Product;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProductRepository extends ReactiveCrudRepository<Product, Long> {
    Flux<Product> findByCategoryId(Long categoryId);

    // Discontinued products stay in the table for order history but are not
    // catalog any more, so the default listing hides them. The count query has
    // to carry the same predicate as the page query or PagedResult reports a
    // total the caller can never page to.
    @Query("SELECT * FROM product WHERE discontinued = FALSE ORDER BY id LIMIT :size OFFSET :offset")
    Flux<Product> findAllPaged(@Param("size") int size, @Param("offset") long offset);

    @Query("SELECT COUNT(*) FROM product WHERE discontinued = FALSE")
    Mono<Long> countActive();

    // Admin views need to see what has been retired in order to bring it back.
    @Query("SELECT * FROM product ORDER BY id LIMIT :size OFFSET :offset")
    Flux<Product> findAllPagedIncludingDiscontinued(@Param("size") int size, @Param("offset") long offset);

    @Modifying
    @Query("UPDATE product SET discontinued = TRUE, updated_at = now() WHERE id = :id")
    Mono<Integer> markDiscontinued(@Param("id") Long id);
}
