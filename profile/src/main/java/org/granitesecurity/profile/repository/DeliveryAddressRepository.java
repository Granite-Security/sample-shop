package org.granitesecurity.profile.repository;

import org.granitesecurity.profile.domain.DeliveryAddress;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface DeliveryAddressRepository extends ReactiveCrudRepository<DeliveryAddress, Long> {

    Flux<DeliveryAddress> findByUsername(String username);

    Mono<DeliveryAddress> findByIdAndUsername(Long id, String username);

    Mono<Void> deleteByIdAndUsername(Long id, String username);
}
