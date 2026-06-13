package org.granitesecurity.shop.repository;

import org.granitesecurity.shop.domain.Product;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;

public interface ProductRepository extends ReactiveCrudRepository<Product, Long> {
    Flux<Product> findByCategoryId(Long categoryId);

    @Query("SELECT * FROM product ORDER BY id LIMIT :size OFFSET :offset")
    Flux<Product> findAllPaged(@Param("size") int size, @Param("offset") long offset);
}
