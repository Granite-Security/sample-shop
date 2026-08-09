package org.granitesecurity.accounting.repository;

import org.granitesecurity.accounting.domain.Fact;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface FactRepository extends ReactiveCrudRepository<Fact, Long> {

    /**
     * The prerequisite lookup. Deriving a revenue journal on delivery needs the order's
     * total from OrderPlaced and its gift funding from Spent, and both are facts this
     * service already stored — it never asks shop or balance for them (D25).
     */
    @Query("""
            SELECT * FROM fact
             WHERE aggregate_id = :aggregateId AND event_type = :eventType
             ORDER BY occurred_at, id
            """)
    Flux<Fact> findByAggregateAndType(String aggregateId, String eventType);

    /**
     * Facts waiting on a prerequisite that had not arrived yet. Oldest first, so a chain
     * (order → payment → delivery) resolves in one sweep rather than one per poll.
     */
    @Query("""
            SELECT * FROM fact
             WHERE status = 'UNPOSTED'
             ORDER BY occurred_at, id
             LIMIT :limit
            """)
    Flux<Fact> findUnposted(int limit);

    @Modifying
    @Query("UPDATE fact SET status = 'POSTED', journal_id = :journalId, last_error = NULL WHERE id = :id")
    Mono<Integer> markPosted(Long id, UUID journalId);

    @Modifying
    @Query("UPDATE fact SET status = 'IGNORED', last_error = :reason WHERE id = :id")
    Mono<Integer> markIgnored(Long id, String reason);

    /**
     * Deliberately no staleness rule and no give-up threshold (D23). {@code attempts}
     * counts, it never expires: a fact that cannot be posted yet is a fact waiting for
     * its prerequisite, and dropping it would silently lose money from the books. It
     * shows up on /reconcile instead, where a human can see it.
     */
    @Modifying
    @Query("UPDATE fact SET attempts = attempts + 1, last_error = :error WHERE id = :id")
    Mono<Integer> recordAttempt(Long id, String error);

    @Query("SELECT COUNT(*) FROM fact WHERE status = 'UNPOSTED'")
    Mono<Long> countUnposted();
}
