package org.granitesecurity.accounting.service;

import org.granitesecurity.accounting.domain.Journal;
import org.granitesecurity.accounting.repository.JournalLineRepository;
import org.granitesecurity.accounting.repository.JournalRepository;
import org.granitesecurity.accounting.service.PostingOutcome.PostingLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Straight-line depreciation of the one stated asset (§14.3, D32): CHF 5,000 over ten
 * years, nil residual, CHF 41.67 a month.
 *
 * <p>A recurring journal on a schedule, not an event — nothing in the domain can emit
 * "a month of an asset's life elapsed" (§16.2).
 *
 * <p>Two things a naive schedule gets wrong, and both are handled here:
 * <ul>
 *   <li><b>It must stop.</b> Guarded on accumulated depreciation reaching cost, not on a
 *       date, so a missed month cannot depreciate the asset past zero — it just finishes
 *       later.</li>
 *   <li><b>It must balance at the end.</b> 41.67 × 120 is 5,000.40, forty rappen more than
 *       the asset cost. The final period posts the balancing figure — CHF 41.27 — not the
 *       monthly rate. Any schedule that posts a fixed rounded amount every period
 *       over-depreciates.</li>
 * </ul>
 */
@Service
public class DepreciationService {

    private static final Logger log = LoggerFactory.getLogger(DepreciationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    public static final String EVENT_TYPE = "Depreciation";

    @Value("${accounting.depreciation.cost-minor:500000}")
    private long costMinor;

    @Value("${accounting.depreciation.monthly-minor:4167}")
    private long monthlyMinor;

    @Value("${accounting.depreciation.life-months:120}")
    private int lifeMonths;

    private final JournalRepository journalRepository;
    private final JournalLineRepository journalLineRepository;
    private final JournalService journalService;

    public DepreciationService(JournalRepository journalRepository,
                               JournalLineRepository journalLineRepository,
                               JournalService journalService) {
        this.journalRepository = journalRepository;
        this.journalLineRepository = journalLineRepository;
        this.journalService = journalService;
    }

    @Transactional
    public Mono<Journal> postFor(String periodCode) {
        return journalRepository.countScheduled(periodCode, EVENT_TYPE).flatMap(existing -> {
            if (existing > 0) {
                return Mono.empty();
            }
            return journalLineRepository.netCreditBalance(Accounts.ACCUM_DEPRECIATION)
                    .flatMap(accumulated -> {
                        long remaining = costMinor - accumulated;
                        if (remaining <= 0) {
                            // The asset is fully depreciated. The 121st run posts nothing,
                            // which is the whole point of guarding on the balance.
                            log.debug("Equipment fully depreciated; nothing to post for {}", periodCode);
                            return Mono.<Journal>empty();
                        }
                        // The balancing figure in the final month, the monthly rate otherwise.
                        long amount = Math.min(monthlyMinor, remaining);

                        Map<String, Object> assumptions = new LinkedHashMap<>();
                        assumptions.put("basis", "straight line, nil residual (D32)");
                        assumptions.put("costMinor", costMinor);
                        assumptions.put("lifeMonths", lifeMonths);
                        assumptions.put("monthlyMinor", monthlyMinor);
                        assumptions.put("accumulatedBeforeMinor", accumulated);
                        if (amount != monthlyMinor) {
                            assumptions.put("finalPeriod", true);
                            assumptions.put("note", "balancing figure, not the monthly rate: "
                                    + "a fixed rounded amount every period over-depreciates");
                        }

                        Instant occurredAt = endOf(periodCode);
                        return journalService.post(periodCode, false, occurredAt,
                                        Journal.SOURCE_SCHEDULE, EVENT_TYPE, null,
                                        "Depreciation for " + periodCode, null,
                                        List.of(PostingLine.debit(Accounts.DEPRECIATION, amount),
                                                PostingLine.credit(Accounts.ACCUM_DEPRECIATION, amount)),
                                        MAPPER.writeValueAsString(assumptions))
                                .doOnSuccess(j -> log.info("Depreciated {} rappen for {} ({} of {} accumulated)",
                                        amount, periodCode, accumulated + amount, costMinor));
                    });
        });
    }

    private static Instant endOf(String periodCode) {
        YearMonth month = YearMonth.parse(periodCode, PERIOD);
        LocalDate lastDay = month.atEndOfMonth();
        return lastDay.plusDays(1).atStartOfDay(PeriodService.ZURICH).toInstant().minusMillis(1);
    }
}
