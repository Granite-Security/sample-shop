package org.granitesecurity.shop.repository;

import org.granitesecurity.shop.domain.Voucher;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface VoucherRepository extends ReactiveCrudRepository<Voucher, Long> {

    /** Codes are stored upper-case and trimmed (V12), so callers must normalise before asking. */
    Mono<Voucher> findByCode(String code);

    /**
     * Admin listing, newest first, including revoked and expired ones — the same
     * reason {@code findAllOrdered} shows retired packaging options: a list that
     * hides what was withdrawn gives no way to see what happened.
     */
    @Query("SELECT * FROM voucher ORDER BY created_at DESC, id DESC")
    Flux<Voucher> findAllNewestFirst();
}
