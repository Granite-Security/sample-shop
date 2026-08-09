package org.granitesecurity.accounting.repository;

import org.granitesecurity.accounting.domain.Period;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface PeriodRepository extends ReactiveCrudRepository<Period, String> {

    @Query("SELECT * FROM period ORDER BY code")
    Flux<Period> findAllOrdered();

    /**
     * Where a late fact goes when its own period has closed (§6): the earliest period
     * still open, so a prior-period adjustment lands as early as it legitimately can
     * rather than always in today's month.
     */
    @Query("SELECT * FROM period WHERE status = 'OPEN' ORDER BY code LIMIT 1")
    Mono<Period> findEarliestOpen();

    /** A period with facts still unposted is not finished, whatever the calendar says. */
    @Query("""
            SELECT COUNT(*) FROM fact
             WHERE status = 'UNPOSTED'
               AND to_char(occurred_at AT TIME ZONE 'Europe/Zurich', 'YYYY-MM') = :code
            """)
    Mono<Long> countUnpostedIn(String code);
}
