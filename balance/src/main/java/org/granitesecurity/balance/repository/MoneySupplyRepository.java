package org.granitesecurity.balance.repository;

import org.granitesecurity.balance.domain.LedgerEntry;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.Repository;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Money creation, per bucket (docs/finance/accounting.md §9.3).
 *
 * <p>balance owns this because gifts, top-ups and the funding split exist nowhere else.
 * shop can say what was sold; only this service can say how much of it was paid for with
 * money we conjured.
 *
 * <p><b>Never add these figures to shop's cash panel.</b> {@code house:shop} is balance-paid
 * orders only, while the sales figure is all orders — they overlap and neither contains the
 * other. Putting them in one total is the most likely bug on the reports page.
 */
public interface MoneySupplyRepository extends Repository<LedgerEntry, Long> {

    /**
     * The house leg is the one to sum: a house account's negative balance is what it has
     * issued. Signs are flipped for issuance so the report reads in positives — "we conjured
     * CHF 400 in August", not "house:gift went to −40000" — the same convention as
     * ReconcileReport.
     *
     * <p>{@code AT TIME ZONE 'Europe/Zurich'} on the bucket expression, matching shop's cash
     * view exactly: the two panels sit side by side and must cut time the same way, or a
     * sale at 00:30 on the first lands in different months in each.
     */
    @Query("""
            SELECT date_trunc(:trunc, e.created_at AT TIME ZONE 'Europe/Zurich')          AS bucket,
                   -COALESCE(SUM(e.amount_minor) FILTER (WHERE a.username = 'house:gift'), 0)   AS gifted_minor,
                   -COALESCE(SUM(e.amount_minor) FILTER (WHERE a.username = 'house:topup'), 0)  AS topped_up_minor,
                    COALESCE(SUM(e.amount_minor) FILTER (WHERE a.username = 'house:shop'), 0)   AS spent_minor,
                   -COALESCE(SUM(e.amount_minor) FILTER (WHERE a.username = 'house:refund'), 0) AS refunded_minor
              FROM ledger_entry e
              JOIN account a ON a.id = e.account_id
             WHERE a.kind = 'HOUSE'
               AND e.created_at >= :from AND e.created_at < :to
             GROUP BY 1
             ORDER BY 1
            """)
    Flux<HouseBucket> houseMovements(String trunc, Instant from, Instant to);

    /**
     * The funding split of what was spent, from the debit legs that recorded it. Backed is
     * the remainder — amount less gift less credit — and is deliberately not stored, so it
     * cannot disagree with the other two.
     */
    @Query("""
            SELECT date_trunc(:trunc, created_at AT TIME ZONE 'Europe/Zurich') AS bucket,
                   COALESCE(SUM(-amount_minor), 0)        AS spent_minor,
                   COALESCE(SUM(gift_funded_minor), 0)    AS gift_minor,
                   COALESCE(SUM(credit_funded_minor), 0)  AS credit_minor
              FROM ledger_entry
             WHERE kind = 'SPEND' AND amount_minor < 0
               AND created_at >= :from AND created_at < :to
             GROUP BY 1
             ORDER BY 1
            """)
    Flux<FundingBucket> fundingSplit(String trunc, Instant from, Instant to);

    /**
     * {@code bucket} is a timestamp without time zone: the expression already converted to
     * Zurich local time, so it is a bucket label and must not be read back as an instant.
     */
    record HouseBucket(LocalDateTime bucket, long giftedMinor, long toppedUpMinor,
                       long spentMinor, long refundedMinor) {}

    record FundingBucket(LocalDateTime bucket, long spentMinor, long giftMinor, long creditMinor) {}
}
