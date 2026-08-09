package org.granitesecurity.accounting.repository;

import org.granitesecurity.accounting.domain.ChartAccount;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface ChartAccountRepository extends ReactiveCrudRepository<ChartAccount, String> {

    /** Code order is account order: assets, liabilities, equity, revenue, expense. */
    @Query("SELECT * FROM account ORDER BY code")
    Flux<ChartAccount> findAllOrdered();
}
