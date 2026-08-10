package org.granitesecurity.accounting.service;

import org.granitesecurity.accounting.domain.Period;
import org.granitesecurity.accounting.repository.PeriodRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

/**
 * Runs the period-end estimates (§6). Scheduled, not consumed: nothing in the domain can
 * emit "the month ended".
 *
 * <p>Targets periods whose <b>last day has passed</b> and that are still open. Provisioning
 * for a month on its second day would provide against almost no revenue, and closing the
 * month before its estimates exist would freeze it incomplete.
 *
 * <p>Runs daily rather than monthly on purpose. A monthly trigger that fires while the pod
 * is restarting is a month with no provision and nobody noticing; the postings are
 * idempotent per period, so a daily run either does the work or finds it already done.
 */
@Component
public class PeriodEndJob {

    private static final Logger log = LoggerFactory.getLogger(PeriodEndJob.class);

    private final PeriodRepository periodRepository;
    private final EstimatesService estimatesService;

    public PeriodEndJob(PeriodRepository periodRepository, EstimatesService estimatesService) {
        this.periodRepository = periodRepository;
        this.estimatesService = estimatesService;
    }

    @Scheduled(cron = "${accounting.estimates.cron:0 30 2 * * *}", zone = "Europe/Zurich")
    void run() {
        postElapsed().subscribe();
    }

    Mono<Void> postElapsed() {
        LocalDate today = LocalDate.now(PeriodService.ZURICH);
        return periodRepository.findAllOrdered()
                .filter(period -> !period.isClosed() && !period.getEndsOn().isAfter(today))
                .concatMap(this::estimatesFor)
                .then()
                .onErrorResume(e -> {
                    log.error("Period-end estimates failed", e);
                    return Mono.empty();
                });
    }

    /**
     * The return provision first, then the allowance. Order matters only for legibility —
     * they touch different accounts — but reading a period's entries in the order an
     * accountant would expect is worth the one line it costs.
     */
    public Mono<Void> estimatesFor(Period period) {
        return estimatesService.postReturnProvision(period.getCode())
                .then(estimatesService.postCreditLossAllowance(period.getCode()))
                .then();
    }
}
