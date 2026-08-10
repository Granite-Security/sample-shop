package org.granitesecurity.accounting.service;

import org.granitesecurity.accounting.domain.Period;
import org.granitesecurity.accounting.repository.JournalLineRepository;
import org.granitesecurity.accounting.repository.JournalLineRepository.AccrualRow;
import org.granitesecurity.accounting.repository.PeriodRepository;
import org.granitesecurity.accounting.service.EstimatesService.EclReport;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What we <em>earned</em> (§10, step 13) — the accrual view, read from booked journals
 * rather than computed from orders.
 *
 * <p>Different numbers on different dates from the cash view, and that is the point. Revenue
 * appears in the month of delivery, not payment; gifted credit reduces it; expected returns
 * reduce it in the month of the sale rather than surprising a later one.
 *
 * <p>The credit-loss allowance is <b>outside</b> the totals and is not bucketed. It is a
 * balance-sheet position as of a date, not a flow through a month, and keeping it out of
 * the totals is what stops anyone netting it against revenue (D11).
 */
@Service
public class AccrualReportService {

    private static final DateTimeFormatter MONTH_LABEL =
            DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_LABEL =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    private final JournalLineRepository journalLineRepository;
    private final PeriodRepository periodRepository;
    private final EstimatesService estimatesService;
    private final PeriodService periodService;

    public AccrualReportService(JournalLineRepository journalLineRepository,
                                PeriodRepository periodRepository,
                                EstimatesService estimatesService,
                                PeriodService periodService) {
        this.journalLineRepository = journalLineRepository;
        this.periodRepository = periodRepository;
        this.estimatesService = estimatesService;
        this.periodService = periodService;
    }

    public Mono<AccrualReport> report(String granularityRaw, String fromRaw, String toRaw) {
        Granularity granularity = Granularity.from(granularityRaw);
        LocalDate today = LocalDate.now(PeriodService.ZURICH);
        LocalDate to = date(toRaw, "to", today.plusDays(1));
        LocalDate from = date(fromRaw, "from", to.minusMonths(12));
        if (!from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'from' must be before 'to'");
        }
        List<LocalDate> starts = granularity.starts(from, to);
        Instant fromInstant = from.atStartOfDay(PeriodService.ZURICH).toInstant();
        Instant toInstant = to.atStartOfDay(PeriodService.ZURICH).toInstant();

        return Mono.zip(
                        journalLineRepository.accrualByBucket(granularity.sqlUnit, fromInstant, toInstant)
                                .collect(Collectors.toMap(r -> r.bucket().toLocalDate(),
                                        Function.identity(), (a, b) -> a, LinkedHashMap::new)),
                        periodRepository.findAllOrdered()
                                .collect(Collectors.toMap(Period::getCode, Period::getStatus,
                                        (a, b) -> a, LinkedHashMap::new)),
                        estimatesService.creditLoss())
                .map(t -> assemble(granularity, from, to, starts, t.getT1(), t.getT2(), t.getT3()));
    }

    private AccrualReport assemble(Granularity granularity, LocalDate from, LocalDate to,
                                   List<LocalDate> starts, Map<LocalDate, AccrualRow> rows,
                                   Map<String, String> periodStatus, EclReport creditLoss) {
        List<AccrualBucket> buckets = new ArrayList<>(starts.size());
        long gross = 0, gift = 0, returns = 0, delivered = 0;

        for (LocalDate start : starts) {
            AccrualRow row = rows.get(start);
            long bGross = row == null ? 0 : row.revenueGrossMinor();
            long bGift = row == null ? 0 : row.contraGiftMinor();
            long bReturns = row == null ? 0 : row.contraReturnsMinor();
            long bDelivered = row == null ? 0 : row.deliveredCount();

            buckets.add(new AccrualBucket(start, granularity.label(start),
                    // A bucket spanning more than one period reports the status of the one
                    // it starts in. Weeks and years can straddle; saying CLOSED when half of
                    // it is open would be worse than saying which month it belongs to.
                    periodStatus.getOrDefault(PERIOD.format(start), "OPEN"),
                    bGross, bGift, bReturns, bGross - bGift - bReturns, bDelivered));

            gross += bGross;
            gift += bGift;
            returns += bReturns;
            delivered += bDelivered;
        }

        return new AccrualReport(granularity.sqlUnit, "CHF", periodService.booksOpenOn(),
                from, to, buckets, creditLoss,
                new AccrualTotals(gross, gift, returns, gross - gift - returns, delivered));
    }

    private static LocalDate date(String raw, String field, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid '" + field + "' date: " + raw);
        }
    }

    /** @param periodStatus OPEN or CLOSED — the page marks frozen buckets */
    public record AccrualBucket(LocalDate bucket, String label, String periodStatus,
                                long revenueGrossMinor, long contraGiftMinor,
                                long contraReturnsMinor, long netRevenueMinor,
                                long deliveredCount) {}

    public record AccrualTotals(long revenueGrossMinor, long contraGiftMinor,
                                long contraReturnsMinor, long netRevenueMinor,
                                long deliveredCount) {}

    /**
     * @param booksOpenedOn before this date there are no books at all; the page says "not yet
     *                      booked" rather than showing a zero that looks like no sales (D22)
     * @param creditLoss    deliberately outside {@code totals}: an allowance is a position as
     *                      of a date and must never be netted against revenue (D11)
     */
    public record AccrualReport(String granularity, String currency, LocalDate booksOpenedOn,
                                LocalDate from, LocalDate to, List<AccrualBucket> buckets,
                                EclReport creditLoss, AccrualTotals totals) {}

    enum Granularity {
        YEAR("year"), MONTH("month"), WEEK("week");

        final String sqlUnit;

        Granularity(String sqlUnit) {
            this.sqlUnit = sqlUnit;
        }

        static Granularity from(String raw) {
            if (raw == null || raw.isBlank()) {
                return MONTH;
            }
            for (Granularity g : values()) {
                if (g.sqlUnit.equalsIgnoreCase(raw)) {
                    return g;
                }
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown granularity: " + raw);
        }

        List<LocalDate> starts(LocalDate from, LocalDate to) {
            List<LocalDate> starts = new ArrayList<>();
            for (LocalDate start = truncate(from); start.isBefore(to); start = next(start)) {
                starts.add(start);
                if (starts.size() > 1200) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Range is too wide for granularity " + sqlUnit);
                }
            }
            return starts;
        }

        LocalDate truncate(LocalDate date) {
            return switch (this) {
                case YEAR -> date.withDayOfYear(1);
                case MONTH -> date.withDayOfMonth(1);
                case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            };
        }

        LocalDate next(LocalDate start) {
            return switch (this) {
                case YEAR -> start.plusYears(1);
                case MONTH -> start.plusMonths(1);
                case WEEK -> start.plusWeeks(1);
            };
        }

        String label(LocalDate start) {
            return switch (this) {
                case YEAR -> String.valueOf(start.getYear());
                case MONTH -> MONTH_LABEL.format(start);
                case WEEK -> DAY_LABEL.format(start);
            };
        }
    }
}
