package org.granitesecurity.shop.repository;

import org.granitesecurity.shop.domain.PackagingGroup;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface PackagingGroupRepository extends ReactiveCrudRepository<PackagingGroup, Long> {
    Mono<PackagingGroup> findByCode(String code);
}
