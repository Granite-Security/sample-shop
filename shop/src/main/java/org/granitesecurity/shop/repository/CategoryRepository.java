package org.granitesecurity.shop.repository;

import org.granitesecurity.shop.domain.Category;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface CategoryRepository extends ReactiveCrudRepository<Category, Long> {
}
