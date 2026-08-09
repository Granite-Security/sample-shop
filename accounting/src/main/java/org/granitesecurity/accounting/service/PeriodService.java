package org.granitesecurity.accounting.service;

import org.granitesecurity.accounting.domain.Period;
import org.granitesecurity.accounting.repository.PeriodRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Periods, and where a fact belongs (docs/finance/accounting.md §6, step 6).
 *
 * <p>Closing a period is what a computed-on-demand report can never do, and it is the whole
 * reason these books are stored rather than derived: a closed month is frozen, and a late
 * fact posts to the open one as a prior-period adjustment instead of silently changing a
 * number someone already read.
 */
@Service
public class PeriodService {

    private static final Logger log = LoggerFactory.getLogger(PeriodService.class);

    /** Every bucket in this system is cut in Zurich (D15), so periods are too. */
    public static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");

    private static final DateTimeFormatter CODE = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * The books start on a date (D22). Kafka retention has already deleted history, so
     * there is nothing to replay and no honest way to book a past that was never booked.
     * Facts older than this are recorded and not posted, and every report before it says
     * "cash view only".
     */
    @Value("${accounting.books-open-on:2026-09-01}")
    private LocalDate booksOpenOn;

    private final PeriodRepository periodRepository;

    public PeriodService(PeriodRepository periodRepository) {
        this.periodRepository = periodRepository;
    }

    public LocalDate booksOpenOn() {
        return booksOpenOn;
    }

    public boolean isBeforeBooksOpen(Instant occurredAt) {
        return occurredAt.atZone(ZURICH).toLocalDate().isBefore(booksOpenOn);
    }

    public Flux<Period> all() {
        return periodRepository.findAllOrdered();
    }

    /**
     * Where this fact posts, and whether that is its own period.
     *
     * <p>Three cases. Its own period is open — post there. Its own period does not exist
     * yet — create it open and post there. Its own period is closed — post to the earliest
     * period still open and flag the entry as a prior-period adjustment, because the
     * alternative is either reopening a closed month or dropping the fact, and both are
     * worse than an entry that says what it is.
     */
    public Mono<Placement> placeFor(Instant occurredAt) {
        String code = codeFor(occurredAt);
        return periodRepository.findById(code)
                .switchIfEmpty(Mono.defer(() -> create(code)))
                .flatMap(period -> {
                    if (!period.isClosed()) {
                        return Mono.just(new Placement(period.getCode(), false));
                    }
                    return periodRepository.findEarliestOpen()
                            .map(open -> {
                                log.info("Fact dated in closed period {} posts to {} as a prior-period adjustment",
                                        code, open.getCode());
                                return new Placement(open.getCode(), true);
                            })
                            // Every period this fact could belong to is closed and none is
                            // open. Opening the current month is the only correct answer:
                            // refusing would leave the fact unbooked forever.
                            .switchIfEmpty(Mono.defer(() -> create(codeFor(Instant.now()))
                                    .map(created -> new Placement(created.getCode(), true))));
                });
    }

    private Mono<Period> create(String code) {
        YearMonth month = YearMonth.parse(code, CODE);
        Period period = new Period();
        period.setCode(code);
        period.setStartsOn(month.atDay(1));
        period.setEndsOn(month.plusMonths(1).atDay(1));
        period.setStatus(Period.OPEN);
        return periodRepository.save(period.markNew())
                .doOnSuccess(p -> log.info("Opened accounting period {}", code))
                // Two consumers can race to open the same month; the primary key decides
                // and the loser reads back rather than failing the fact.
                .onErrorResume(e -> periodRepository.findById(code)
                        .switchIfEmpty(Mono.error(e)));
    }

    /**
     * Closing is one-way and there is no reopen, deliberately: a month that can be reopened
     * is not closed, and "we will reopen it just this once" is how a restated period
     * becomes routine.
     */
    public Mono<Period> close(String code, String actor) {
        return periodRepository.findById(code)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No such period: " + code)))
                .flatMap(period -> {
                    if (period.isClosed()) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "Period " + code + " is already closed"));
                    }
                    // A period holding facts that could not be posted is not finished,
                    // whatever the calendar says. Closing it would freeze a month knowing
                    // entries are still missing from it.
                    return periodRepository.countUnpostedIn(code).flatMap(unposted -> {
                        if (unposted > 0) {
                            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                    "Period " + code + " has " + unposted
                                            + " unposted fact(s); resolve them before closing"));
                        }
                        period.setStatus(Period.CLOSED);
                        period.setClosedAt(Instant.now());
                        period.setClosedBy(actor);
                        return periodRepository.save(period)
                                .doOnSuccess(p -> log.info("Closed accounting period {} ({})", code, actor));
                    });
                });
    }

    public static String codeFor(Instant instant) {
        return CODE.format(instant.atZone(ZURICH));
    }

    /** @param priorPeriod the fact's own period was closed and this is an adjustment */
    public record Placement(String periodCode, boolean priorPeriod) {}
}
