package org.granitesecurity.accounting.service;

import org.granitesecurity.accounting.domain.Journal;
import org.granitesecurity.accounting.repository.FactRepository;
import org.granitesecurity.accounting.repository.JournalLineRepository;
import org.granitesecurity.accounting.repository.JournalLineRepository.OpenReceivable;
import org.granitesecurity.accounting.repository.JournalRepository;
import org.granitesecurity.accounting.service.PostingOutcome.PostingLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two period-end estimates (docs/finance/accounting.md §6, step 7).
 *
 * <p><b>Estimates are postings, not queries.</b> Each is an entry made on a date under an
 * assumption set, and it stays as it was made. Recomputing last year's provision with
 * today's rates silently rewrites history and looks like a feature — it is the same disease
 * as deriving revenue on every page load, which is why these books exist.
 *
 * <p>Both are idempotent per period. Both are marked {@code estimated} and carry the
 * assumptions they used, because an assumed number rendered like a measured one is this
 * whole design's stated failure mode (D21).
 */
@Service
public class EstimatesService {

    private static final Logger log = LoggerFactory.getLogger(EstimatesService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    public static final String RETURN_PROVISION = "ReturnProvision";
    public static final String CREDIT_LOSS = "CreditLossAllowance";

    /** How far back the return rate looks. One number, one query, no new data (§2.2). */
    @Value("${accounting.returns.trailing-days:180}")
    private int trailingDays;

    /**
     * Used only until enough history exists to measure one. Stated rather than zero: a 0%
     * return rate is itself an assumption, and a more misleading one.
     */
    @Value("${accounting.returns.default-rate:0.02}")
    private BigDecimal defaultReturnRate;

    @Value("${accounting.returns.minimum-observations:20}")
    private int minimumObservations;

    private final JournalRepository journalRepository;
    private final JournalLineRepository journalLineRepository;
    private final FactRepository factRepository;
    private final JournalService journalService;
    private final EclProperties ecl;

    public EstimatesService(JournalRepository journalRepository,
                            JournalLineRepository journalLineRepository,
                            FactRepository factRepository,
                            JournalService journalService,
                            EclProperties ecl) {
        this.journalRepository = journalRepository;
        this.journalLineRepository = journalLineRepository;
        this.factRepository = factRepository;
        this.journalService = journalService;
        this.ecl = ecl;
    }

    /**
     * Provides for returns in the month of the sale (IFRS 15.55, §2.2): revenue is
     * recognised only for what we expect to keep, so an expected return is a refund
     * liability now rather than a surprise later.
     *
     * <p>The cash view behaves the opposite way and that is correct — there a refund lands
     * where the money moved, so a cash month can go negative while an accrual month should
     * not, having already provided. If the two disagree in direction, the rate is wrong.
     * That is information, not a bug to suppress.
     */
    @Transactional
    public Mono<Journal> postReturnProvision(String periodCode) {
        return journalRepository.countScheduled(periodCode, RETURN_PROVISION).flatMap(existing -> {
            if (existing > 0) {
                log.debug("Return provision already posted for {}", periodCode);
                return Mono.empty();
            }
            return Mono.zip(journalLineRepository.netCreditIn(periodCode, Accounts.REVENUE), returnRate())
                    .flatMap(t -> {
                        long revenue = t.getT1();
                        Rate rate = t.getT2();
                        long provision = BigDecimal.valueOf(revenue)
                                .multiply(rate.rate())
                                .setScale(0, RoundingMode.HALF_UP)
                                .longValueExact();
                        if (revenue <= 0 || provision <= 0) {
                            log.debug("Nothing to provide for in {} (revenue {})", periodCode, revenue);
                            return Mono.empty();
                        }
                        Map<String, Object> assumptions = new LinkedHashMap<>();
                        assumptions.put("basis", "expected returns on revenue recognised in this period");
                        assumptions.put("returnRate", rate.rate());
                        assumptions.put("rateSource", rate.source());
                        assumptions.put("trailingDays", trailingDays);
                        assumptions.put("delivered", rate.delivered());
                        assumptions.put("refunded", rate.refunded());
                        assumptions.put("revenueMinor", revenue);

                        return post(periodCode, RETURN_PROVISION, List.of(
                                        PostingLine.debit(Accounts.CONTRA_RETURNS, provision),
                                        PostingLine.credit(Accounts.REFUND_LIABILITY, provision)),
                                "Expected returns on " + periodCode, assumptions);
                    });
        });
    }

    /**
     * Moves the loss allowance to what the provision matrix says it should be (§2.6).
     *
     * <p>The <b>movement</b>, not the balance: the allowance is a standing position, and
     * posting the target every period would multiply it. It can go either way — a receivable
     * that gets repaid releases allowance back to income.
     *
     * <p>Presented as an expense on its own line, never as a reduction of revenue
     * (IAS 1.82(ba), D11). The temptation is a single "real revenue" number; the result is a
     * figure no accountant can reproduce.
     */
    @Transactional
    public Mono<Journal> postCreditLossAllowance(String periodCode) {
        return journalRepository.countScheduled(periodCode, CREDIT_LOSS).flatMap(existing -> {
            if (existing > 0) {
                log.debug("Credit-loss allowance already posted for {}", periodCode);
                return Mono.empty();
            }
            return Mono.zip(creditLoss(), journalLineRepository.netCreditBalance(Accounts.ECL_ALLOWANCE))
                    .flatMap(t -> {
                        EclReport report = t.getT1();
                        long held = t.getT2();
                        long movement = report.allowanceMinor() - held;
                        if (movement == 0) {
                            return Mono.empty();
                        }
                        Map<String, Object> assumptions = new LinkedHashMap<>();
                        assumptions.put("basis", "IFRS 9 simplified approach, lifetime ECL, provision matrix");
                        assumptions.put("asOf", report.asOf());
                        assumptions.put("bands", report.bands());
                        assumptions.put("allowanceTargetMinor", report.allowanceMinor());
                        assumptions.put("allowanceHeldMinor", held);
                        assumptions.put("note", "loss rates are assumptions, not measurements — "
                                + "there is no repayment history to derive them from yet");

                        List<PostingLine> lines = movement > 0
                                ? List.of(PostingLine.debit(Accounts.IMPAIRMENT, movement),
                                          PostingLine.credit(Accounts.ECL_ALLOWANCE, movement))
                                // Releasing allowance: exposure fell, so the expense reverses.
                                : List.of(PostingLine.debit(Accounts.ECL_ALLOWANCE, -movement),
                                          PostingLine.credit(Accounts.IMPAIRMENT, -movement));

                        return post(periodCode, CREDIT_LOSS, lines,
                                "Expected credit loss movement for " + periodCode, assumptions);
                    });
        });
    }

    /**
     * The matrix with its working shown: the bands, the exposure in each and the resulting
     * allowance. A bare "expected credit loss: CHF 68" is not reviewable.
     */
    public Mono<EclReport> creditLoss() {
        Instant asOf = Instant.now();
        return journalLineRepository.openReceivables(Accounts.RECEIVABLES)
                .collectList()
                .map(open -> {
                    List<BandView> views = new ArrayList<>();
                    long totalAllowance = 0;
                    long totalExposure = 0;

                    for (EclProperties.Band band : ecl.getBands()) {
                        long exposure = 0;
                        for (OpenReceivable receivable : open) {
                            long age = Duration.between(receivable.openedAt(), asOf).toDays();
                            if (bandFor(age) == band) {
                                exposure += receivable.exposureMinor();
                            }
                        }
                        long allowance = BigDecimal.valueOf(exposure)
                                .multiply(band.getLossRate())
                                .setScale(0, RoundingMode.HALF_UP)
                                .longValueExact();
                        totalAllowance += allowance;
                        totalExposure += exposure;
                        views.add(new BandView(band.getMaxAgeDays(), band.getLossRate(), exposure, allowance));
                    }
                    return new EclReport(ecl.getAsOf(), true, totalExposure, totalAllowance, views);
                });
    }

    /**
     * The rate, and where it came from. Below a handful of observations a measured rate is
     * noise dressed as a measurement, so the stated default is used and the entry says so.
     */
    private Mono<Rate> returnRate() {
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(trailingDays));
        return factRepository.returnCounts(from, to).map(counts -> {
            if (counts.delivered() < minimumObservations) {
                return new Rate(defaultReturnRate, "assumed default — fewer than "
                        + minimumObservations + " deliveries observed",
                        counts.delivered(), counts.refunded());
            }
            BigDecimal rate = BigDecimal.valueOf(counts.refunded())
                    .divide(BigDecimal.valueOf(counts.delivered()), 4, RoundingMode.HALF_UP);
            return new Rate(rate, "measured over the trailing " + trailingDays + " days",
                    counts.delivered(), counts.refunded());
        });
    }

    private EclProperties.Band bandFor(long ageDays) {
        for (EclProperties.Band band : ecl.getBands()) {
            if (band.covers(ageDays)) {
                return band;
            }
        }
        return ecl.getBands().isEmpty() ? null : ecl.getBands().getLast();
    }

    private Mono<Journal> post(String periodCode, String eventType, List<PostingLine> lines,
                               String memo, Map<String, Object> assumptions) {
        // Dated at the last instant of the period it provides for, not at the moment the
        // job happened to run: the entry belongs to the month it is about.
        Instant occurredAt = endOf(periodCode);
        return journalService.post(periodCode, false, occurredAt, Journal.SOURCE_SCHEDULE,
                        eventType, null, memo, null, lines, MAPPER.writeValueAsString(assumptions))
                .doOnSuccess(j -> log.info("Posted {} for {}", eventType, periodCode));
    }

    private static Instant endOf(String periodCode) {
        YearMonth month = YearMonth.parse(periodCode, PERIOD);
        LocalDate lastDay = month.atEndOfMonth();
        return lastDay.plusDays(1).atStartOfDay(PeriodService.ZURICH).toInstant().minusMillis(1);
    }

    private record Rate(BigDecimal rate, String source, long delivered, long refunded) {}

    /** @param estimated always true; the UI keys its "assumption, not measurement" styling off it */
    public record EclReport(LocalDate asOf, boolean estimated, long exposureMinor,
                            long allowanceMinor, List<BandView> bands) {}

    public record BandView(Integer maxAgeDays, BigDecimal lossRate, long exposureMinor, long allowanceMinor) {}
}
