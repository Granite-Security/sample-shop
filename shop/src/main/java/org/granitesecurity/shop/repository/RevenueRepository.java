package org.granitesecurity.shop.repository;

import org.granitesecurity.shop.domain.CustomerOrder;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * The cash view, aggregated in SQL (docs/finance/accounting.md D17, §9.1).
 *
 * <p>Three aggregations rather than one query with a {@code CASE}: a sale and its refund
 * are separate events in separate buckets, and doing both in one pass buys a
 * {@code FULL OUTER JOIN} on {@code date_trunc} for nothing. The service joins them by
 * bucket instead.
 *
 * <p>{@code AT TIME ZONE 'Europe/Zurich'} is explicit on every bucket expression because
 * {@code date_trunc} otherwise cuts in the session timezone — UTC on the pods — and a
 * Zurich sale at 00:30 on the first of the month would land in the previous one (D15).
 * The panels on the reports page sit side by side and must cut time the same way.
 *
 * <p>Status lists deliberately do not appear here. {@code paid_at IS NOT NULL} is the
 * predicate, and it stays correct as the status graph grows (D4).
 */
public interface RevenueRepository extends Repository<CustomerOrder, Long> {

    /**
     * Sales, bucketed by when the order first reached {@code PAID} (D5). A later return
     * is a separate negative line, never a deletion of the sale, so nothing here excludes
     * refunded orders.
     */
    @Query("""
            SELECT date_trunc(:trunc, paid_at AT TIME ZONE 'Europe/Zurich') AS bucket,
                   COUNT(*)   AS order_count,
                   SUM(total) AS amount_total
              FROM customer_order
             WHERE paid_at IS NOT NULL
               AND currency = :currency
               AND paid_at >= :from
               AND paid_at <  :to
             GROUP BY 1
             ORDER BY 1
            """)
    Flux<BucketAggregate> sales(@Param("trunc") String trunc,
                                @Param("currency") String currency,
                                @Param("from") Instant from,
                                @Param("to") Instant to);

    /**
     * Refunds, bucketed by when the money actually went back — {@code REIMBURSED}, not
     * {@code RETURNED} (D6). A refund the provider accepted and then failed at the bank
     * walks the order back to {@code RETURNED}, and {@code refunded_at} stays null until
     * a retry completes, so it correctly stops counting.
     */
    @Query("""
            SELECT date_trunc(:trunc, refunded_at AT TIME ZONE 'Europe/Zurich') AS bucket,
                   COUNT(*)   AS order_count,
                   SUM(total) AS amount_total
              FROM customer_order
             WHERE refunded_at IS NOT NULL
               AND currency = :currency
               AND refunded_at >= :from
               AND refunded_at <  :to
             GROUP BY 1
             ORDER BY 1
            """)
    Flux<BucketAggregate> refunds(@Param("trunc") String trunc,
                                  @Param("currency") String currency,
                                  @Param("from") Instant from,
                                  @Param("to") Instant to);

    /**
     * Refunds requested but not yet settled, bucketed against the sale they will reverse.
     *
     * <p>Bucketed by {@code paid_at} on purpose: a pending return has no refund date yet,
     * and the useful question is how much of a given month's sales is already claimed
     * back. It is shown beside gross, never subtracted from it — until the money moves,
     * nothing has moved.
     */
    @Query("""
            SELECT date_trunc(:trunc, paid_at AT TIME ZONE 'Europe/Zurich') AS bucket,
                   COUNT(*)   AS order_count,
                   SUM(total) AS amount_total
              FROM customer_order
             WHERE status = 'RETURNED'
               AND paid_at IS NOT NULL
               AND currency = :currency
               AND paid_at >= :from
               AND paid_at <  :to
             GROUP BY 1
             ORDER BY 1
            """)
    Flux<BucketAggregate> pendingReturns(@Param("trunc") String trunc,
                                         @Param("currency") String currency,
                                         @Param("from") Instant from,
                                         @Param("to") Instant to);

    /** Which currencies have orders at all, so the UI shows a selector only when it must (D14). */
    @Query("SELECT DISTINCT currency FROM customer_order ORDER BY currency")
    Flux<String> currenciesWithOrders();

    /**
     * {@code bucket} comes back as a {@code timestamp without time zone} because the
     * expression already converted to Zurich local time — it is a bucket label, not an
     * instant, and must not be re-interpreted as one.
     */
    record BucketAggregate(LocalDateTime bucket, long orderCount, BigDecimal amountTotal) {}
}
