package org.granitesecurity.delivery.repository;

import org.granitesecurity.delivery.domain.Delivery;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * The paginated listing behind {@code GET /api/delivery}.
 *
 * <p>Built on {@link R2dbcEntityTemplate} rather than the derived-query methods on
 * {@link DeliveryRepository}, because both the filters and the sort are chosen at
 * request time. Derived queries would need one method per combination — four filter
 * shapes times four sorts — and {@code ORDER BY} cannot be a bind parameter, so the
 * alternative is string-concatenating a column name into SQL. The criteria API
 * builds both halves from typed values instead.
 */
@Repository
public class DeliveryQueryRepository {

    private final R2dbcEntityTemplate template;

    public DeliveryQueryRepository(R2dbcEntityTemplate template) {
        this.template = template;
    }

    /**
     * The sortable columns, as an allow-list. A column name reaching {@code ORDER BY}
     * is the one part of this query that is not a bind parameter, so unknown input
     * falls back to {@link #ORDER_ID} rather than being passed through.
     */
    public enum SortKey {
        ORDER_ID("orderId"),
        CREATED_AT("createdAt");

        private final String property;

        SortKey(String property) {
            this.property = property;
        }

        public static SortKey from(String raw) {
            for (SortKey key : values()) {
                if (key.property.equalsIgnoreCase(raw)) {
                    return key;
                }
            }
            return ORDER_ID;
        }
    }

    public Flux<Delivery> findPage(String status, String paymentStatus, Instant from, Instant to,
                                   SortKey sortKey, boolean ascending, int size, long offset) {
        Sort.Direction direction = ascending ? Sort.Direction.ASC : Sort.Direction.DESC;
        // "id" is the tiebreaker, not decoration: created_at is not unique, and a sort
        // that leaves ties unordered lets a row appear on two pages or on neither as
        // the reader walks them.
        Sort sort = Sort.by(direction, sortKey.property).and(Sort.by(direction, "id"));
        return template.select(Delivery.class)
                .matching(Query.query(criteria(status, paymentStatus, from, to))
                        .sort(sort)
                        .limit(size)
                        .offset(offset))
                .all();
    }

    /** Rows matching the filters across every page — the {@code total} the pager needs. */
    public Mono<Long> count(String status, String paymentStatus, Instant from, Instant to) {
        return template.select(Delivery.class)
                .matching(Query.query(criteria(status, paymentStatus, from, to)))
                .count();
    }

    /**
     * The window is half-open, {@code [from, to)} — the callers pass a date picker's
     * day as {@code from} and the following midnight as {@code to}, so an inclusive
     * upper bound would pull in the next day's first instant.
     */
    private Criteria criteria(String status, String paymentStatus, Instant from, Instant to) {
        Criteria criteria = Criteria.empty();
        if (status != null) {
            criteria = criteria.and("status").is(status);
        }
        if (paymentStatus != null) {
            criteria = criteria.and("paymentStatus").is(paymentStatus);
        }
        if (from != null) {
            criteria = criteria.and("createdAt").greaterThanOrEquals(from);
        }
        if (to != null) {
            criteria = criteria.and("createdAt").lessThan(to);
        }
        return criteria;
    }
}
