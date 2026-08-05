package org.granitesecurity.balance.repository;

import org.granitesecurity.balance.domain.Idempotency;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IdempotencyRepository extends ReactiveCrudRepository<Idempotency, String> {
}
