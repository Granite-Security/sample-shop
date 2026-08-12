package org.granitesecurity.shop.repository;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Who has used which voucher (docs/finance/vouchers.md V8).
 *
 * <p>Explicit SQL over a {@link DatabaseClient} rather than a Spring Data repository,
 * because the table has no surrogate id: its primary key is the composite
 * {@code (voucher_id, username)}, and that composite <em>is</em> the once-per-user
 * rule. A mapped entity would need an {@code @Id} this table does not have, and
 * {@code save()} on an assigned key issues an UPDATE that matches no rows and reports
 * success — turning a duplicate redemption into a silent one.
 */
@Repository
public class VoucherRedemptionRepository {

    private final DatabaseClient client;

    public VoucherRedemptionRepository(DatabaseClient client) {
        this.client = client;
    }

    /**
     * Claims the voucher, or fails with a duplicate-key error.
     *
     * <p>The insert is the check: a SELECT-then-INSERT loses the race between two
     * checkouts submitted at the same instant, and a unique violation cannot. Runs
     * inside the caller's placement transaction, so a voucher is never consumed by an
     * order that then rolls back.
     */
    public Mono<Long> insert(Long voucherId, String username, Long orderId) {
        return client.sql("""
                        INSERT INTO voucher_redemption (voucher_id, username, order_id, redeemed_at)
                        VALUES (:voucherId, :username, :orderId, :redeemedAt)
                        """)
                .bind("voucherId", voucherId)
                .bind("username", username)
                .bind("orderId", orderId)
                .bind("redeemedAt", Instant.now())
                .fetch()
                .rowsUpdated();
    }

    public Mono<Long> countByVoucherId(Long voucherId) {
        return client.sql("SELECT COUNT(*) FROM voucher_redemption WHERE voucher_id = :voucherId")
                .bind("voucherId", voucherId)
                .map(row -> row.get(0, Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    /**
     * Advisory only — it lets checkout say "already used" before the shopper fills in
     * an address. The authoritative answer is {@link #insert}'s constraint.
     */
    public Mono<Boolean> existsByVoucherIdAndUsername(Long voucherId, String username) {
        return client.sql("""
                        SELECT EXISTS (SELECT 1 FROM voucher_redemption
                                        WHERE voucher_id = :voucherId AND username = :username)
                        """)
                .bind("voucherId", voucherId)
                .bind("username", username)
                .map(row -> row.get(0, Boolean.class))
                .one()
                .defaultIfEmpty(false);
    }
}
