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
     * The debit, with the credit policy inlined as the WHERE predicate, and the
     * gift/backed/credit split of this spend computed from the same row in the same
     * statement (docs/finance/accounting.md §5.2).
     *
     * <p><b>This is the whole concurrency guard.</b> Checking the balance and then
     * updating it in two statements loses the race between two simultaneous spends;
     * as one statement, Postgres serialises them and the loser updates zero rows.
     * An empty result means declined (docs/finance/finance.md §4.2).
     *
     * <p>{@code minBalanceBefore} carries the policy: 1 means "must be strictly
     * positive before this debit", which is today's AnyPositiveBalancePolicy.
     *
     * <p>The {@code FOR UPDATE} in the CTE is doing real work and is not belt-and-braces.
     * The split has to be reported against the values the debit actually applied to, and
     * a plain {@code UPDATE ... FROM (SELECT ...)} would join to the statement's start
     * snapshot: under a concurrent spend on the same account, Postgres re-evaluates the
     * UPDATE against the new row version but the joined subquery would still carry the
     * old pool, and the split would be recorded against a balance that no longer existed.
     * {@code FOR UPDATE} makes the second spender wait and then re-read, so the two
     * drawdowns are sequential rather than both drawing the same gifted francs.
     *
     * <p>The pool is clamped at zero rather than allowed to go negative: a spend larger
     * than the pool empties it, and the remainder is backed or lent money by definition.
     */
    @Query("""
            WITH locked AS (
                SELECT id, balance_minor, gift_pool_minor, negative_since
                  FROM account WHERE id = :id FOR UPDATE
            ), updated AS (
                UPDATE account a
                   SET balance_minor   = l.balance_minor - :amountMinor,
                       gift_pool_minor = GREATEST(0, l.gift_pool_minor - :amountMinor),
                       negative_since  = CASE
                           WHEN l.balance_minor - :amountMinor >= 0 THEN NULL
                           WHEN l.negative_since IS NULL          THEN now()
                           ELSE l.negative_since END,
                       updated_at      = now()
                  FROM locked l
                 WHERE a.id = l.id AND l.balance_minor >= :minBalanceBefore
             RETURNING l.balance_minor AS balance_before, l.gift_pool_minor AS pool_before
            )
            SELECT LEAST(pool_before, :amountMinor) AS gift_drawn,
                   GREATEST(0, :amountMinor - GREATEST(balance_before, 0)) AS credit_drawn
              FROM updated
            """)
    Mono<Drawdown> debitIf(Long id, long amountMinor, long minBalanceBefore);

    /**
     * House accounts and every credit: no policy applies, money is arriving.
     *
     * <p>Clears {@code negative_since} when the balance reaches zero or above, so the
     * receivable ageing that IFRS 9 buckets by cannot outlive the receivable itself.
     */
    @Modifying
    @Query("""
            UPDATE account
               SET balance_minor  = balance_minor + :amountMinor,
                   negative_since = CASE WHEN balance_minor + :amountMinor >= 0
                                         THEN NULL ELSE negative_since END,
                   updated_at     = now()
             WHERE id = :id
            """)
    Mono<Long> credit(Long id, long amountMinor);

    /**
     * Adds conjured money to an account's pool: the whole of a GIFT, the drawn portion
     * of a TRANSFER, or the gifted part of a REFUND being returned to where it came from.
     *
     * <p>A separate statement from {@link #credit} on purpose. The two legs of a movement
     * are applied in ascending id order to make deadlock impossible, so the credit can run
     * before the debit whose drawdown it needs. By the time this runs both rows are already
     * locked by this transaction, so touching the recipient again takes no new lock and
     * cannot reorder anything.
     *
     * <p>Never called for a house account — the CHECK constraint in balance/003 enforces
     * that, and it is the difference between "conjured money is held here" and "conjured
     * money came from here".
     */
    @Modifying
    @Query("""
            UPDATE account SET gift_pool_minor = gift_pool_minor + :giftMinor, updated_at = now()
             WHERE id = :id AND kind = 'USER'
            """)
    Mono<Long> addGiftPool(Long id, long giftMinor);

    /** Unconditional debit, for house accounts — they are allowed to go negative. */
    @Modifying
    @Query("UPDATE account SET balance_minor = balance_minor - :amountMinor, updated_at = now() WHERE id = :id")
    Mono<Long> debitUnchecked(Long id, long amountMinor);

    Flux<Account> findAllByKind(String kind);

    /** House accounts first, then users by name — the order the treasury page wants. */
    @Query("SELECT * FROM account ORDER BY kind DESC, username")
    Flux<Account> findAllForTreasury();

    /** Invariant: conjured money still held must equal what the ledger says entered pools. */
    @Query("SELECT COALESCE(SUM(gift_pool_minor), 0) FROM account")
    Mono<Long> sumGiftPools();

    /**
     * How much of this spend was conjured and how much was lent, as applied.
     *
     * <p>Backed money is the remainder — {@code amount - gift - credit} — and is
     * deliberately not returned: a third number is a third thing that can disagree
     * with the other two.
     */
    record Drawdown(long giftDrawn, long creditDrawn) {
        /** A house debit: issuance has no funding to split. */
        public static final Drawdown NONE = new Drawdown(0, 0);
    }
}
