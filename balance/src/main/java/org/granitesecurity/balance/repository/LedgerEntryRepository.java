package org.granitesecurity.balance.repository;

import org.granitesecurity.balance.domain.LedgerEntry;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface LedgerEntryRepository extends ReactiveCrudRepository<LedgerEntry, Long> {

    @Query("""
            SELECT * FROM ledger_entry WHERE account_id = :accountId
            ORDER BY created_at DESC, id DESC
            LIMIT :size OFFSET :offset
            """)
    Flux<LedgerEntry> findByAccount(Long accountId, int size, long offset);

    /** Invariant 1: over the whole table this must be exactly zero. */
    @Query("SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_entry")
    Mono<Long> sumAll();

    /** Invariant 2: this must equal the account's cached balance_minor. */
    @Query("SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_entry WHERE account_id = :accountId")
    Mono<Long> sumForAccount(Long accountId);
}
