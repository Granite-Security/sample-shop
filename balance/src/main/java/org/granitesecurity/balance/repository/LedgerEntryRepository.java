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

    /**
     * Every movement, newest first — the treasury feed. Deliberately returns the
     * entity rather than a join: the account list is small, so the caller resolves
     * account_id to a username itself and this needs no projection mapping.
     */
    @Query("SELECT * FROM ledger_entry ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset")
    Flux<LedgerEntry> findAllNewestFirst(int size, long offset);

    /** Invariant 1: over the whole table this must be exactly zero. */
    @Query("SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_entry")
    Mono<Long> sumAll();

    /** Invariant 2: this must equal the account's cached balance_minor. */
    @Query("SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_entry WHERE account_id = :accountId")
    Mono<Long> sumForAccount(Long accountId);

    /**
     * How much gifted money is still out on this order: drawn by its spend, less
     * anything a previous refund already put back.
     *
     * <p>Netting the refunds off is what makes a repeated or partial refund safe. A
     * refund must restore conjured money to the pool it came from — otherwise one
     * refunded gift-funded order quietly turns conjured money into backed money — but
     * it must not restore more than was drawn, or the pool would manufacture gift
     * credit that was never issued.
     */
    @Query("""
            SELECT COALESCE(SUM(CASE
                       WHEN kind = 'SPEND'  AND amount_minor < 0 THEN  gift_funded_minor
                       WHEN kind = 'REFUND' AND amount_minor > 0 THEN -gift_funded_minor
                       ELSE 0 END), 0)
              FROM ledger_entry
             WHERE account_id = :accountId AND reference = :reference
            """)
    Mono<Long> giftOutstandingOn(Long accountId, String reference);

    /**
     * Invariant (docs/finance/accounting.md §12.1): conjured money is either still in
     * someone's pool or has been spent. Every leg that carries gifted francs into a pool
     * counts positive, every drawdown negative, and the result must equal the sum of the
     * pools themselves.
     *
     * <p>If this drifts, the funding split is lying — and since the split feeds
     * contra-revenue, so is revenue.
     */
    @Query("""
            SELECT COALESCE(SUM(CASE WHEN amount_minor > 0 THEN gift_funded_minor
                                     ELSE -gift_funded_minor END), 0)
              FROM ledger_entry
            """)
    Mono<Long> netGiftIntoPools();

    /** Invariant: a split can never exceed the movement it splits. Must be zero. */
    @Query("""
            SELECT COUNT(*) FROM ledger_entry
             WHERE gift_funded_minor + credit_funded_minor > abs(amount_minor)
            """)
    Mono<Long> countFundingViolations();

    /** Of everything spent on orders, the conjured part (§5.3). */
    @Query("""
            SELECT COALESCE(SUM(gift_funded_minor), 0) FROM ledger_entry
             WHERE kind = 'SPEND' AND amount_minor < 0
            """)
    Mono<Long> spentFromGift();

    /** Of everything spent on orders, the part we lent rather than held (§5.3). */
    @Query("""
            SELECT COALESCE(SUM(credit_funded_minor), 0) FROM ledger_entry
             WHERE kind = 'SPEND' AND amount_minor < 0
            """)
    Mono<Long> spentFromCredit();
}
