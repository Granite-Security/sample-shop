package org.granitesecurity.shop.service;

import org.granitesecurity.shop.dto.RevenueGranularity;
import org.granitesecurity.shop.dto.RevenueReport;
import org.granitesecurity.shop.dto.RevenueReport.RevenueBucket;
import org.granitesecurity.shop.dto.RevenueReport.RevenueTotals;
import org.granitesecurity.shop.repository.RevenueRepository;
import org.granitesecurity.shop.repository.RevenueRepository.BucketAggregate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The cash half of the revenue reports (docs/finance/accounting.md §11, step 11).
 *
 * <p>Ships ahead of the accounting service on purpose: it answers "did we sell?" on its
 * own, it reconciles against the balance ledger, and it needs nothing that is not already
 * in {@code customer_order}. What it must never be called is <em>revenue</em> — that is
 * an accrual figure recognised on delivery, and it lives elsewhere.
 */
@Service
public class RevenueService {

    /**
     * Every bucket in every panel of the reports page is cut in this zone (D15). Pods run
     * UTC, so leaving it out would put a Zurich sale at 00:30 on the 1st in the previous
     * month — and would do so inconsistently across the three services once the other
     * panels land.
     */
    static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");

    /** balance is CHF-only, and shop went USD -> CHF on 2026-08-01 (shop/008). */
    private static final String DEFAULT_CURRENCY = "CHF";

    private final RevenueRepository revenueRepository;

    public RevenueService(RevenueRepository revenueRepository) {
        this.revenueRepository = revenueRepository;
    }

    /**
     * @param granularityRaw {@code year|month|week}, defaulting to month
     * @param currencyRaw    ISO code; summing across currencies is meaningless and there is
     *                       no FX anywhere in this system (D14), so one series is returned
     * @param fromRaw        ISO date, inclusive, defaulting to twelve months back
     * @param toRaw          ISO date, <em>exclusive</em>, defaulting to tomorrow so today counts
     */
    public Mono<RevenueReport> report(String granularityRaw, String currencyRaw,
                                      String fromRaw, String toRaw) {
        RevenueGranularity granularity = RevenueGranularity.from(granularityRaw);
        String currency = normaliseCurrency(currencyRaw);

        LocalDate today = LocalDate.now(ZURICH);
        LocalDate to = parseDate(toRaw, "to", today.plusDays(1));
        LocalDate from = parseDate(fromRaw, "from", to.minusMonths(12));
        if (!from.isBefore(to)) {
            throw new ShopException("'from' must be before 'to' (to is exclusive)");
        }

        List<LocalDate> bucketStarts = bucketStarts(granularity, from, to);

        Instant fromInstant = from.atStartOfDay(ZURICH).toInstant();
        Instant toInstant = to.atStartOfDay(ZURICH).toInstant();
        String trunc = granularity.sqlUnit();

        return Mono.zip(
                        collect(revenueRepository.sales(trunc, currency, fromInstant, toInstant)),
                        collect(revenueRepository.refunds(trunc, currency, fromInstant, toInstant)),
                        collect(revenueRepository.pendingReturns(trunc, currency, fromInstant, toInstant)))
                .map(t -> assemble(granularity, currency, from, to, bucketStarts,
                        t.getT1(), t.getT2(), t.getT3()));
    }

    /** Which currencies actually have orders, so the UI hides the selector when there is one. */
    public Mono<List<String>> currencies() {
        return revenueRepository.currenciesWithOrders().collectList();
    }

    private RevenueReport assemble(RevenueGranularity granularity, String currency,
                                   LocalDate from, LocalDate to, List<LocalDate> bucketStarts,
                                   Map<LocalDate, BucketAggregate> sales,
                                   Map<LocalDate, BucketAggregate> refunds,
                                   Map<LocalDate, BucketAggregate> pending) {
        List<RevenueBucket> buckets = new ArrayList<>(bucketStarts.size());
        long grossTotal = 0, refundedTotal = 0, pendingTotal = 0, orderTotal = 0, refundCountTotal = 0;

        // Gaps are filled here rather than with generate_series: the sequence is already
        // needed in Java to label the buckets, and a month with no orders must render as a
        // zero row so a gap in sales is visible as a gap rather than as missing data (D18).
        for (LocalDate start : bucketStarts) {
            long gross = minor(sales.get(start));
            long refunded = minor(refunds.get(start));
            long pendingMinor = minor(pending.get(start));
            long orderCount = count(sales.get(start));
            long refundCount = count(refunds.get(start));

            buckets.add(new RevenueBucket(start, granularity.label(start),
                    gross, refunded, gross - refunded, orderCount, refundCount, pendingMinor));

            grossTotal += gross;
            refundedTotal += refunded;
            pendingTotal += pendingMinor;
            orderTotal += orderCount;
            refundCountTotal += refundCount;
        }

        // Totals are computed here, over the same window, because a browser re-summing
        // the buckets is how a rounding story starts.
        return new RevenueReport(granularity, currency, from, to, buckets,
                new RevenueTotals(grossTotal, refundedTotal, grossTotal - refundedTotal,
                        orderTotal, refundCountTotal, pendingTotal));
    }

    private Mono<Map<LocalDate, BucketAggregate>> collect(reactor.core.publisher.Flux<BucketAggregate> rows) {
        return rows.collect(Collectors.toMap(
                row -> row.bucket().toLocalDate(),
                Function.identity(),
                (a, b) -> a,
                LinkedHashMap::new));
    }

    private static List<LocalDate> bucketStarts(RevenueGranularity granularity,
                                                LocalDate from, LocalDate to) {
        List<LocalDate> starts = new ArrayList<>();
        long limit = granularity.maxBuckets();
        for (LocalDate start = granularity.truncate(from); start.isBefore(to);
             start = granularity.next(start)) {
            starts.add(start);
            if (starts.size() > limit) {
                throw new ShopException(
                        "Range is too wide for granularity " + granularity.sqlUnit()
                                + " (limit " + limit + " buckets)");
            }
        }
        return starts;
    }

    /**
     * Decimal francs to rappen at the service boundary (D16). {@code total} is
     * {@code NUMERIC(10,2)}, so this is exact and {@code longValueExact} is the assertion
     * that says so — a rounding here would be money quietly changing shape.
     */
    private static long minor(BucketAggregate row) {
        if (row == null || row.amountTotal() == null) {
            return 0L;
        }
        BigDecimal amount = row.amountTotal().setScale(2, RoundingMode.UNNECESSARY);
        return amount.movePointRight(2).longValueExact();
    }

    private static long count(BucketAggregate row) {
        return row == null ? 0L : row.orderCount();
    }

    private static String normaliseCurrency(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_CURRENCY;
        }
        String currency = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) {
            throw new ShopException("Invalid currency: " + raw);
        }
        return currency;
    }

    private static LocalDate parseDate(String raw, String field, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new ShopException("Invalid '" + field + "' date: " + raw + " (expected YYYY-MM-DD)",
                    HttpStatus.BAD_REQUEST, "Bad Request");
        }
    }
}
