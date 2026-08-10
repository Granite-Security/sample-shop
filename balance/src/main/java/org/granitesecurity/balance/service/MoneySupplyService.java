package org.granitesecurity.balance.service;

import org.granitesecurity.balance.dto.MoneySupplyReport;
import org.granitesecurity.balance.dto.MoneySupplyReport.Bucket;
import org.granitesecurity.balance.dto.MoneySupplyReport.Totals;
import org.granitesecurity.balance.repository.AccountRepository;
import org.granitesecurity.balance.repository.MoneySupplyRepository;
import org.granitesecurity.balance.repository.MoneySupplyRepository.FundingBucket;
import org.granitesecurity.balance.repository.MoneySupplyRepository.HouseBucket;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * The money-supply half of the reports page (§9.3, step 12).
 *
 * <p>Answers two of the four questions the page exists for: how much money did we conjure,
 * and was it actually spent. Both are aggregations of rows that already exist — the funding
 * split has been recorded on every debit since balance/003.
 */
@Service
public class MoneySupplyService {

    /** Every panel on the page cuts time in this zone, or they cannot be read side by side. */
    static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");

    private static final DateTimeFormatter MONTH_LABEL =
            DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_LABEL =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final MoneySupplyRepository moneySupplyRepository;
    private final AccountRepository accountRepository;

    public MoneySupplyService(MoneySupplyRepository moneySupplyRepository,
                              AccountRepository accountRepository) {
        this.moneySupplyRepository = moneySupplyRepository;
        this.accountRepository = accountRepository;
    }

    public Mono<MoneySupplyReport> report(String granularityRaw, String fromRaw, String toRaw) {
        Granularity granularity = Granularity.from(granularityRaw);
        LocalDate today = LocalDate.now(ZURICH);
        LocalDate to = date(toRaw, "to", today.plusDays(1));
        LocalDate from = date(fromRaw, "from", to.minusMonths(12));
        if (!from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'from' must be before 'to' (to is exclusive)");
        }
        List<LocalDate> starts = granularity.starts(from, to);
        Instant fromInstant = from.atStartOfDay(ZURICH).toInstant();
        Instant toInstant = to.atStartOfDay(ZURICH).toInstant();

        return Mono.zip(
                        moneySupplyRepository.houseMovements(granularity.sqlUnit, fromInstant, toInstant)
                                .collect(Collectors.toMap(h -> h.bucket().toLocalDate(),
                                        Function.identity(), (a, b) -> a, LinkedHashMap::new)),
                        moneySupplyRepository.fundingSplit(granularity.sqlUnit, fromInstant, toInstant)
                                .collect(Collectors.toMap(f -> f.bucket().toLocalDate(),
                                        Function.identity(), (a, b) -> a, LinkedHashMap::new)),
                        // Conjured money still held. Read from the pools rather than derived
                        // from the buckets: it is a position now, not the sum of a window,
                        // and a window that starts after a gift would miss it entirely.
                        accountRepository.sumGiftPools())
                .map(t -> assemble(granularity, from, to, starts, t.getT1(), t.getT2(), t.getT3()));
    }

    private MoneySupplyReport assemble(Granularity granularity, LocalDate from, LocalDate to,
                                       List<LocalDate> starts,
                                       Map<LocalDate, HouseBucket> house,
                                       Map<LocalDate, FundingBucket> funding,
                                       long giftedOutstanding) {
        List<Bucket> buckets = new ArrayList<>(starts.size());
        long gifted = 0, toppedUp = 0, spent = 0, refunded = 0, fromGift = 0, fromBacked = 0, fromCredit = 0;

        for (LocalDate start : starts) {
            HouseBucket h = house.get(start);
            FundingBucket f = funding.get(start);

            long bGifted = h == null ? 0 : h.giftedMinor();
            long bTopped = h == null ? 0 : h.toppedUpMinor();
            long bSpent = h == null ? 0 : h.spentMinor();
            long bRefunded = h == null ? 0 : h.refundedMinor();
            long bGift = f == null ? 0 : f.giftMinor();
            long bCredit = f == null ? 0 : f.creditMinor();
            // The remainder, never stored: gift + backed + credit = spend is an invariant,
            // and a third stored column is a third thing that can disagree with the other two.
            long bBacked = (f == null ? 0 : f.spentMinor()) - bGift - bCredit;

            buckets.add(new Bucket(start, granularity.label(start), bGifted, bTopped, bSpent,
                    bRefunded, bGift, bBacked, bCredit));

            gifted += bGifted;
            toppedUp += bTopped;
            spent += bSpent;
            refunded += bRefunded;
            fromGift += bGift;
            fromBacked += bBacked;
            fromCredit += bCredit;
        }

        return new MoneySupplyReport(granularity.sqlUnit, from, to, buckets,
                new Totals(gifted, toppedUp, spent, refunded, fromGift, fromBacked, fromCredit,
                        giftedOutstanding));
    }

    private static LocalDate date(String raw, String field, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid '" + field + "' date: " + raw + " (expected YYYY-MM-DD)");
        }
    }

    /**
     * An enum, because {@code granularity} reaches SQL as {@code date_trunc}'s first
     * argument — the one part of the query that is not a value. Nothing outside these three
     * constants can get there.
     */
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown granularity: " + raw + " (expected year, month or week)");
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
