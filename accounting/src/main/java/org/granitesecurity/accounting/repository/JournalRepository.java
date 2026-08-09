package org.granitesecurity.accounting.repository;

import org.granitesecurity.accounting.domain.Journal;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface JournalRepository extends ReactiveCrudRepository<Journal, UUID> {

    @Query("""
            SELECT * FROM journal
             WHERE occurred_at >= :from AND occurred_at < :to
             ORDER BY occurred_at DESC, posted_at DESC
             LIMIT :limit OFFSET :offset
            """)
    Flux<Journal> findInWindow(Instant from, Instant to, int limit, long offset);

    @Query("SELECT COUNT(*) FROM journal WHERE occurred_at >= :from AND occurred_at < :to")
    Mono<Long> countInWindow(Instant from, Instant to);

    /**
     * Invariant: debits equal credits in every entry. A constraint trigger already makes
     * a violation impossible to commit, so this must always return zero — it is here
     * because an invariant nobody checks is an invariant nobody notices losing.
     */
    @Query("""
            SELECT COUNT(*) FROM (
                SELECT journal_id FROM journal_line
                 GROUP BY journal_id
                HAVING SUM(debit_minor) <> SUM(credit_minor)
            ) unbalanced
            """)
    Mono<Long> countUnbalanced();
}
