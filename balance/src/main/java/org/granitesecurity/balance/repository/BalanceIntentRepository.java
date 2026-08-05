package org.granitesecurity.balance.repository;

import org.granitesecurity.balance.domain.BalanceIntent;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface BalanceIntentRepository extends ReactiveCrudRepository<BalanceIntent, UUID> {
}
