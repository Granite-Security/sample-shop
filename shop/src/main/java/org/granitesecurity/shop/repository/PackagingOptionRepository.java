package org.granitesecurity.shop.repository;

import org.granitesecurity.shop.domain.PackagingOption;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PackagingOptionRepository extends ReactiveCrudRepository<PackagingOption, Long> {
    Mono<PackagingOption> findByCode(String code);

    // Admin listings show retired options too — otherwise there is no way to find
    // one and put it back, the same trap findAllPagedIncludingDiscontinued avoids
    // for products.
    @Query("SELECT * FROM packaging_option ORDER BY sort_order, id")
    Flux<PackagingOption> findAllOrdered();
}
