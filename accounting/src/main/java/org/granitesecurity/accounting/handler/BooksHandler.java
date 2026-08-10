package org.granitesecurity.accounting.handler;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.granitesecurity.accounting.domain.Journal;
import org.granitesecurity.accounting.domain.JournalLine;
import org.granitesecurity.accounting.domain.Period;
import org.granitesecurity.accounting.repository.FactRepository;
import org.granitesecurity.accounting.repository.JournalLineRepository;
import org.granitesecurity.accounting.repository.JournalLineRepository.TrialBalanceRow;
import org.granitesecurity.accounting.repository.JournalRepository;
import org.granitesecurity.accounting.service.AccrualReportService;
import org.granitesecurity.accounting.service.EstimatesService;
import org.granitesecurity.accounting.service.OpeningBalanceService;
import org.granitesecurity.accounting.service.OpeningBalanceService.OpeningPosition;
import org.granitesecurity.accounting.service.PeriodEndJob;
import org.granitesecurity.accounting.service.PeriodService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The audit trail (docs/finance/accounting.md step 5, item 13) and the period controls
 * (step 6). Read-only apart from closing a period, which freezes a month and cannot
 * create, change or move any amount.
 */
@Service
public class BooksHandler {

    private final JournalRepository journalRepository;
    private final JournalLineRepository journalLineRepository;
    private final FactRepository factRepository;
    private final PeriodService periodService;
    private final EstimatesService estimatesService;
    private final PeriodEndJob periodEndJob;
    private final OpeningBalanceService openingBalanceService;
    private final AccrualReportService accrualReportService;

    public BooksHandler(JournalRepository journalRepository,
                        JournalLineRepository journalLineRepository,
                        FactRepository factRepository,
                        PeriodService periodService,
                        EstimatesService estimatesService,
                        PeriodEndJob periodEndJob,
                        OpeningBalanceService openingBalanceService,
                        AccrualReportService accrualReportService) {
        this.journalRepository = journalRepository;
        this.journalLineRepository = journalLineRepository;
        this.factRepository = factRepository;
        this.periodService = periodService;
        this.estimatesService = estimatesService;
        this.periodEndJob = periodEndJob;
        this.openingBalanceService = openingBalanceService;
        this.accrualReportService = accrualReportService;
    }

    @Operation(operationId = "getAccrualRevenue", summary = "What we earned — the accrual view",
            description = """
                    Admin only. Revenue as booked: recognised on delivery, credited gross, with
                    gifted credit and expected returns shown as separate deductions rather than
                    netted away.

                    Different numbers on different dates from shop's cash view, and that is the
                    point — one says what moved, this says what we earned. Never add the two.

                    `creditLoss` sits outside `totals` deliberately: an allowance is a
                    balance-sheet position as of a date, not a flow through a month, and keeping it
                    out is what stops anyone netting it against revenue. `booksOpenedOn` marks
                    where there are no books at all rather than no sales.""")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One row per bucket, plus totals and the allowance"),
            @ApiResponse(responseCode = "400", description = "Bad granularity or dates", content = @Content()),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN", content = @Content())
    })
    public Mono<ServerResponse> getAccrualRevenue(ServerRequest request) {
        return accrualReportService.report(
                        request.queryParam("granularity").orElse(null),
                        request.queryParam("from").orElse(null),
                        request.queryParam("to").orElse(null))
                .flatMap(report -> ServerResponse.ok().bodyValue(report));
    }

    @Operation(operationId = "postOpeningBalance", summary = "Open the books on a stated position",
            description = """
                    Admin only, and **once**. The books start on a date: Kafka retention has already
                    deleted the history, so there is nothing to replay and no honest way to
                    reconstruct a past that was never booked.

                    The figures are stated by you, not fetched — accounting never calls another
                    service, and an opening balance is a declaration someone is accountable for
                    rather than a number scraped from two databases at whatever moment a job ran.

                    Two of them need care. `storedValueBackedMinor` is the **backed portion only**:
                    Σ(positive balances) − Σ(gift pools), because policy (b) books no liability for
                    gifted credit. And owner's capital is not a field — it is computed as whatever
                    makes the entry balance, and the journal records that it is a plug.

                    A second call is refused. A second opening balance is not a correction, it is a
                    second past; correct the first by reversal.""")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Posted"),
            @ApiResponse(responseCode = "400", description = "An opening balance of nothing", content = @Content()),
            @ApiResponse(responseCode = "409", description = "The books are already open", content = @Content())
    })
    public Mono<ServerResponse> postOpeningBalance(ServerRequest request) {
        return request.principal()
                .cast(Authentication.class)
                .map(auth -> ((Jwt) auth.getCredentials()).getSubject())
                .zipWith(request.bodyToMono(OpeningPosition.class)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "An opening position is required"))))
                .flatMap(t -> openingBalanceService.post(t.getT2(), t.getT1()))
                .flatMap(journal -> ServerResponse.ok().bodyValue(toView(journal, List.of())));
    }

    @Operation(operationId = "getCreditLoss", summary = "The expected-credit-loss matrix, with its working",
            description = """
                    Admin only. The IFRS 9 provision matrix: ageing bands, the exposure in each and
                    the resulting allowance. A bare "expected credit loss: CHF 68" is not reviewable,
                    so the bands come with it.

                    `estimated` is always true and is not decoration: until there is repayment
                    history these loss rates are assumptions, and `asOf` is the date they were set.
                    This is a balance-sheet position as of a date, not a flow through a month, and it
                    is never netted against revenue.""")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bands, exposure and allowance"),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN", content = @Content())
    })
    public Mono<ServerResponse> getCreditLoss(ServerRequest request) {
        return estimatesService.creditLoss()
                .flatMap(report -> ServerResponse.ok().bodyValue(report));
    }

    @Operation(operationId = "runEstimates", summary = "Run the period-end estimates now",
            description = """
                    Admin only. Posts the return provision and the credit-loss movement for one
                    period, exactly as the nightly job would. Idempotent per period: running it
                    twice posts once, and there is no way to amend the first entry if you disagree
                    with it — correct it by reversal, like anything else in these books.

                    Exists because the alternative way to see an estimate is to wait a month.""")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ran; the response says what was posted"),
            @ApiResponse(responseCode = "404", description = "No such period", content = @Content()),
            @ApiResponse(responseCode = "409", description = "The period is closed", content = @Content())
    })
    public Mono<ServerResponse> runEstimates(ServerRequest request) {
        String code = request.pathVariable("code");
        return periodService.require(code)
                .flatMap(period -> {
                    if (period.isClosed()) {
                        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                "Period " + code + " is closed; estimates cannot be added to it"));
                    }
                    return periodEndJob.estimatesFor(period);
                })
                .then(journalRepository.countScheduled(code, EstimatesService.RETURN_PROVISION)
                        .zipWith(journalRepository.countScheduled(code, EstimatesService.CREDIT_LOSS)))
                .flatMap(t -> ServerResponse.ok().bodyValue(Map.of(
                        "period", code,
                        "returnProvisionPosted", t.getT1() > 0,
                        "creditLossPosted", t.getT2() > 0)));
    }

    @Operation(operationId = "getJournals", summary = "Journal entries with their lines",
            description = """
                    Admin only. Newest business date first. Entries are never amended — a
                    correction is a separate reversing entry that points at the original.""")
    @SecurityRequirement(name = "bearer-jwt")
    @Parameter(name = "from", description = "ISO date, inclusive")
    @Parameter(name = "to", description = "ISO date, exclusive")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Journals and their lines"),
            @ApiResponse(responseCode = "400", description = "Bad date or paging", content = @Content()),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN", content = @Content())
    })
    public Mono<ServerResponse> getJournals(ServerRequest request) {
        LocalDate to = date(request, "to", LocalDate.now(PeriodService.ZURICH).plusDays(1));
        LocalDate from = date(request, "from", to.minusMonths(1));
        if (!from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'from' must be before 'to'");
        }
        int size = Math.min(Math.max(intParam(request, "size", 50), 1), 200);
        long offset = (long) Math.max(intParam(request, "page", 0), 0) * size;

        Instant fromInstant = from.atStartOfDay(PeriodService.ZURICH).toInstant();
        Instant toInstant = to.atStartOfDay(PeriodService.ZURICH).toInstant();

        return journalRepository.findInWindow(fromInstant, toInstant, size, offset)
                .collectList()
                .flatMap(journals -> lines(journals).map(byJournal -> journals.stream()
                        .map(j -> toView(j, byJournal.getOrDefault(j.getId(), List.of())))
                        .toList()))
                .zipWith(journalRepository.countInWindow(fromInstant, toInstant))
                .flatMap(t -> ServerResponse.ok().bodyValue(Map.of(
                        "from", from, "to", to, "total", t.getT2(), "journals", t.getT1())));
    }

    @Operation(operationId = "getTrialBalance", summary = "Trial balance for a period",
            description = """
                    Admin only. Debits and credits per account for one period, kept in separate
                    columns: the point is that the two totals are equal, and netting them into
                    one signed number is what stops it being a trial balance.""")
    @SecurityRequirement(name = "bearer-jwt")
    @Parameter(name = "period", description = "YYYY-MM; defaults to the current period")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One row per account, plus totals"),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN", content = @Content())
    })
    public Mono<ServerResponse> getTrialBalance(ServerRequest request) {
        String period = request.queryParam("period")
                .orElseGet(() -> PeriodService.codeFor(Instant.now()));

        return journalLineRepository.trialBalance(period)
                .collectList()
                .flatMap(rows -> {
                    long debits = rows.stream().mapToLong(TrialBalanceRow::debitMinor).sum();
                    long credits = rows.stream().mapToLong(TrialBalanceRow::creditMinor).sum();
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("period", period);
                    body.put("rows", rows);
                    body.put("totalDebitsMinor", debits);
                    body.put("totalCreditsMinor", credits);
                    // The invariant, stated rather than implied. If this is ever false the
                    // trial balance is not "slightly off" — the books are not books.
                    body.put("balanced", debits == credits);
                    return ServerResponse.ok().bodyValue(body);
                });
    }

    @Operation(operationId = "getPeriods", summary = "Accounting periods and their status")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Every period, oldest first"))
    public Mono<ServerResponse> getPeriods(ServerRequest request) {
        return periodService.all().collectList()
                .flatMap(periods -> ServerResponse.ok().bodyValue(Map.of(
                        "booksOpenedOn", periodService.booksOpenOn(),
                        "periods", periods.stream().map(BooksHandler::toView).toList())));
    }

    @Operation(operationId = "closePeriod", summary = "Close a period",
            description = """
                    Admin only, and one-way: there is no reopen. A closed month is frozen, and a
                    fact that arrives afterwards posts to the open period as a prior-period
                    adjustment. Refused while the period still holds unposted facts.""")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Closed"),
            @ApiResponse(responseCode = "404", description = "No such period", content = @Content()),
            @ApiResponse(responseCode = "409", description = "Already closed, or facts still unposted", content = @Content())
    })
    public Mono<ServerResponse> closePeriod(ServerRequest request) {
        String code = request.pathVariable("code");
        return request.principal()
                .cast(Authentication.class)
                .map(auth -> ((Jwt) auth.getCredentials()).getSubject())
                .flatMap(actor -> periodService.close(code, actor))
                .flatMap(period -> ServerResponse.ok().bodyValue(toView(period)));
    }

    @Operation(operationId = "getReconcile", summary = "Prove the books",
            description = """
                    Admin only. Every entry balances and nothing is waiting to be booked — the
                    same discipline as balance's /reconcile, checked the same way: run it after
                    every deploy.""")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "The invariants and their values"))
    public Mono<ServerResponse> getReconcile(ServerRequest request) {
        return Mono.zip(journalRepository.countUnbalanced(), factRepository.countUnposted())
                .flatMap(t -> {
                    long unbalanced = t.getT1();
                    long unposted = t.getT2();
                    Map<String, Object> body = new LinkedHashMap<>();
                    // Unposted facts are not a failure: a delivery consumed before its order
                    // is ordinary and resolves on the next sweep. A count that stays high is
                    // the signal — facts waiting on something that is never coming.
                    body.put("balanced", unbalanced == 0);
                    body.put("unbalancedJournals", unbalanced);
                    body.put("unpostedFacts", unposted);
                    return ServerResponse.ok().bodyValue(body);
                });
    }

    private Mono<Map<UUID, List<JournalLine>>> lines(List<Journal> journals) {
        if (journals.isEmpty()) {
            return Mono.just(Map.of());
        }
        List<UUID> ids = journals.stream().map(Journal::getId).toList();
        return journalLineRepository.findByJournalIds(ids)
                .collect(Collectors.groupingBy(JournalLine::getJournalId));
    }

    private static Map<String, Object> toView(Journal journal, List<JournalLine> lines) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", journal.getId());
        view.put("period", journal.getPeriodCode());
        view.put("occurredAt", journal.getOccurredAt());
        view.put("postedAt", journal.getPostedAt());
        view.put("source", journal.getSource());
        view.put("eventType", journal.getEventType());
        view.put("reference", journal.getReference());
        view.put("memo", journal.getMemo());
        view.put("priorPeriod", journal.isPriorPeriod());
        view.put("estimated", journal.isEstimated());
        view.put("assumptions", journal.getAssumptions());
        view.put("reversesId", journal.getReversesId());
        List<Map<String, Object>> lineViews = new ArrayList<>();
        for (JournalLine line : lines) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("account", line.getAccountCode());
            l.put("debitMinor", line.getDebitMinor());
            l.put("creditMinor", line.getCreditMinor());
            l.put("party", line.getParty());
            l.put("memo", line.getMemo());
            lineViews.add(l);
        }
        view.put("lines", lineViews);
        return view;
    }

    private static Map<String, Object> toView(Period period) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("code", period.getCode());
        view.put("startsOn", period.getStartsOn());
        view.put("endsOn", period.getEndsOn());
        view.put("status", period.getStatus());
        view.put("closedAt", period.getClosedAt());
        view.put("closedBy", period.getClosedBy());
        return view;
    }

    private static LocalDate date(ServerRequest request, String name, LocalDate fallback) {
        return request.queryParam(name).filter(v -> !v.isBlank()).map(value -> {
            try {
                return LocalDate.parse(value.trim());
            } catch (DateTimeParseException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid '" + name + "' date: " + value + " (expected YYYY-MM-DD)");
            }
        }).orElse(fallback);
    }

    private static int intParam(ServerRequest request, String name, int fallback) {
        return request.queryParam(name).filter(v -> !v.isBlank()).map(value -> {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid '" + name + "': " + value);
            }
        }).orElse(fallback);
    }
}
