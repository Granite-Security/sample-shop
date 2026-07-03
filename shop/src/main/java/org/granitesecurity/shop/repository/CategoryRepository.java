package org.granitesecurity.shop.repository;

import org.granitesecurity.shop.domain.Category;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;

public interface CategoryRepository extends ReactiveCrudRepository<Category, Long> {

    @Query("SELECT * FROM category ORDER BY id LIMIT :size OFFSET :offset")
    Flux<Category> findAllPaged(@Param("size") int size, @Param("offset") long offset);
}
