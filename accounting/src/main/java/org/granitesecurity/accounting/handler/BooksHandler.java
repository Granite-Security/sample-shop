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

    public BooksHandler(JournalRepository journalRepository,
                        JournalLineRepository journalLineRepository,
                        FactRepository factRepository,
                        PeriodService periodService) {
        this.journalRepository = journalRepository;
        this.journalLineRepository = journalLineRepository;
        this.factRepository = factRepository;
        this.periodService = periodService;
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
