package org.granitesecurity.balance.repository;

import org.granitesecurity.balance.domain.Account;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface AccountRepository extends ReactiveCrudRepository<Account, Long> {

    Mono<Account> findByUsername(String username);

    /**
     * The debit, with the credit policy inlined as the WHERE predicate.
     *
     * <p><b>This is the whole concurrency guard.</b> Checking the balance and then
     * updating it in two statements loses the race between two simultaneous spends;
     * as one statement, Postgres serialises them and the loser updates zero rows.
     * Callers act on the returned count — zero means declined
     * (docs/finance/finance.md §4.2).
     *
     * <p>{@code minBalanceBefore} carries the policy: 1 means "must be strictly
     * positive before this debit", which is today's AnyPositiveBalancePolicy.
     */
    @Modifying
    @Query("""
            UPDATE account SET balance_minor = balance_minor - :amountMinor, updated_at = now()
            WHERE id = :id AND balance_minor >= :minBalanceBefore
            """)
    Mono<Long> debitIf(Long id, long amountMinor, long minBalanceBefore);

    /** House accounts and every credit: no policy applies, money is arriving. */
    @Modifying
    @Query("UPDATE account SET balance_minor = balance_minor + :amountMinor, updated_at = now() WHERE id = :id")
    Mono<Long> credit(Long id, long amountMinor);

    /** Unconditional debit, for house accounts — they are allowed to go negative. */
    @Modifying
    @Query("UPDATE account SET balance_minor = balance_minor - :amountMinor, updated_at = now() WHERE id = :id")
    Mono<Long> debitUnchecked(Long id, long amountMinor);

    Flux<Account> findAllByKind(String kind);
}
