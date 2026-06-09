package org.granitesecurity.shop.repository;

import org.granitesecurity.shop.domain.Product;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface ProductRepository extends ReactiveCrudRepository<Product, Long> {
    Flux<Product> findByCategoryId(Long categoryId);
}
