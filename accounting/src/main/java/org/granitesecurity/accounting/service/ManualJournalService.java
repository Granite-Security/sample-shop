package org.granitesecurity.accounting.service;

import org.granitesecurity.accounting.domain.Journal;
import org.granitesecurity.accounting.domain.JournalLine;
import org.granitesecurity.accounting.domain.ProcessedEvent;
import org.granitesecurity.accounting.dto.ManualJournalRequests.Expense;
import org.granitesecurity.accounting.dto.ManualJournalRequests.Purchase;
import org.granitesecurity.accounting.dto.ManualJournalRequests.RawJournal;
import org.granitesecurity.accounting.dto.ManualJournalRequests.Reimbursement;
import org.granitesecurity.accounting.repository.ChartAccountRepository;
import org.granitesecurity.accounting.repository.JournalLineRepository;
import org.granitesecurity.accounting.repository.JournalRepository;
import org.granitesecurity.accounting.repository.ProcessedEventRepository;
import org.granitesecurity.accounting.service.PostingOutcome.PostingLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The one place D20's read-only rule is relaxed (D34), and it must stay the only one.
 *
 * <p>Everything here still obeys the rest: entries are append-only, corrections are
 * reversals, the acting user comes from the JWT, and a journal into a closed period is
 * refused rather than quietly rerouted — a human posting into a frozen month is a mistake to
 * report, not an accident to absorb, unlike a late event.
 *
 * <p><b>The boundary worth restating: the bank here is a book account.</b> Reimbursing a
 * manager by crediting their platform balance would be a third door into the ledger, and
 * finance.md is explicit that there are exactly two. If that is ever wanted it is a balance
 * feature with its own house account and its own reconcile line, decided there — not a side
 * effect a journal endpoint may have.
 */
@Service
public class ManualJournalService {

    private static final Logger log = LoggerFactory.getLogger(ManualJournalService.class);

    /** Reuses the consumers' idempotency table: one mechanism, one place to look. */
    private static final String IDEMPOTENCY_TOPIC = "manual";

    private final JournalRepository journalRepository;
    private final JournalLineRepository journalLineRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ChartAccountRepository chartAccountRepository;
    private final JournalService journalService;
    private final PeriodService periodService;

    public ManualJournalService(JournalRepository journalRepository,
                                JournalLineRepository journalLineRepository,
                                ProcessedEventRepository processedEventRepository,
                                ChartAccountRepository chartAccountRepository,
                                JournalService journalService,
                                PeriodService periodService) {
        this.journalRepository = journalRepository;
        this.journalLineRepository = journalLineRepository;
        this.processedEventRepository = processedEventRepository;
        this.chartAccountRepository = chartAccountRepository;
        this.journalService = journalService;
        this.periodService = periodService;
    }

    public Mono<Journal> purchase(Purchase request, String actor) {
        String account = required(request.accountCode(), "accountCode");
        long amount = positive(request.amountMinor());
        // Bought on credit or paid for: different facts, not a presentation choice.
        String funding = request.onCredit() ? Accounts.ACCOUNTS_PAYABLE : Accounts.CASH;
        return post(request.idempotencyKey(), request.occurredAt(), "Purchase",
                request.memo() != null ? request.memo() : "Purchase", actor,
                List.of(PostingLine.debit(account, amount), PostingLine.credit(funding, amount)));
    }

    public Mono<Journal> expense(Expense request, String actor) {
        String account = request.accountCode() != null && !request.accountCode().isBlank()
                ? request.accountCode() : Accounts.OTHER_OPERATING;
        long amount = positive(request.amountMinor());
        boolean personal = request.incurredBy() != null && !request.incurredBy().isBlank();
        // Someone paid for this personally, so we owe a person, not a supplier — and the
        // party goes on the line so "what do we owe Ana?" is a GROUP BY rather than a
        // chart-of-accounts migration every time someone joins (D35).
        PostingLine credit = personal
                ? PostingLine.credit(Accounts.DUE_TO_STAFF, amount, request.incurredBy().trim())
                : PostingLine.credit(Accounts.CASH, amount);

        return post(request.idempotencyKey(), request.occurredAt(), "Expense",
                request.memo() != null ? request.memo() : "Operating expense", actor,
                List.of(PostingLine.debit(account, amount), credit));
    }

    public Mono<Journal> reimbursement(Reimbursement request, String actor) {
        String party = required(request.party(), "party");
        long amount = positive(request.amountMinor());
        return post(request.idempotencyKey(), request.occurredAt(), "Reimbursement",
                request.memo() != null ? request.memo() : "Reimbursed " + party, actor,
                List.of(PostingLine.debit(Accounts.DUE_TO_STAFF, amount, party),
                        PostingLine.credit(Accounts.CASH, amount)));
    }

    public Mono<Journal> raw(RawJournal request, String actor) {
        if (request.lines() == null || request.lines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A journal needs lines");
        }
        List<PostingLine> lines = new ArrayList<>();
        for (RawJournal.Line line : request.lines()) {
            if ((line.debitMinor() == 0) == (line.creditMinor() == 0)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Each line must carry exactly one of debitMinor or creditMinor");
            }
            if (line.debitMinor() < 0 || line.creditMinor() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Amounts are magnitudes: use the other side rather than a negative");
            }
            lines.add(new PostingLine(required(line.accountCode(), "accountCode"),
                    line.debitMinor(), line.creditMinor(), line.party(), line.memo()));
        }
        return post(request.idempotencyKey(), request.occurredAt(), "Journal",
                request.memo() != null ? request.memo() : "Manual journal", actor, lines);
    }

    /**
     * A correction. There is no endpoint that edits a posted journal, for anyone — the
     * database refuses the UPDATE too — so this writes a new entry with every line flipped,
     * pointing at what it reverses.
     */
    @Transactional
    public Mono<Journal> reverse(UUID journalId, String reason, String actor) {
        return journalRepository.findById(journalId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No such journal: " + journalId)))
                .flatMap(original -> journalLineRepository.findByJournalIds(List.of(journalId))
                        .collectList()
                        .flatMap(lines -> {
                            List<PostingLine> flipped = new ArrayList<>();
                            for (JournalLine line : lines) {
                                flipped.add(new PostingLine(line.getAccountCode(),
                                        line.getCreditMinor(), line.getDebitMinor(),
                                        line.getParty(), line.getMemo()));
                            }
                            // Dated today, not on the original's date: the reversal is a
                            // thing that happened now. Backdating it into the period being
                            // corrected is how a closed month quietly changes.
                            return placeAndPost(Instant.now(), Journal.SOURCE_MANUAL, "Reversal",
                                            reason != null ? reason : "Reversal of " + journalId,
                                            actor, flipped, journalId)
                                    .doOnSuccess(j -> log.info("{} reversed journal {} ({})",
                                            actor, journalId, reason));
                        }));
    }

    // ------------------------------------------------------------------ internals

    @Transactional
    public Mono<Journal> post(String idempotencyKey, Instant occurredAt, String type,
                              String memo, String actor, List<PostingLine> lines) {
        String key = IDEMPOTENCY_TOPIC + ':' + type + ':'
                + required(idempotencyKey, "idempotencyKey");
        Instant when = occurredAt != null ? occurredAt : Instant.now();

        return validateAccounts(lines)
                .then(processedEventRepository.existsById(key))
                .flatMap(seen -> {
                    if (Boolean.TRUE.equals(seen)) {
                        // A key that has already been used. Refused rather than replayed:
                        // these are hand-entered, and a caller reusing a key is far more
                        // likely to have made a mistake than to be retrying.
                        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                "This idempotency key has already been used"));
                    }
                    ProcessedEvent marker = new ProcessedEvent();
                    marker.setEventKey(key);
                    marker.setTopic(IDEMPOTENCY_TOPIC);
                    marker.setEventType(type);
                    marker.setProcessedAt(Instant.now());
                    return processedEventRepository.save(marker)
                            .then(placeAndPost(when, Journal.SOURCE_MANUAL, type, memo, actor,
                                    lines, null));
                });
    }

    private Mono<Journal> placeAndPost(Instant occurredAt, String source, String type, String memo,
                                       String actor, List<PostingLine> lines, UUID reverses) {
        return periodService.placeFor(occurredAt).flatMap(placement -> {
            if (placement.priorPeriod()) {
                // Late *events* are routed to the open period and flagged. A human choosing
                // a date inside a closed month is a different thing: it is a mistake, and
                // the honest response is to say so rather than silently move it.
                return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                        "That date falls in a closed period. Post it to an open one, or "
                                + "correct the closed period by reversal."));
            }
            return journalService.post(placement.periodCode(), false, occurredAt, source, type,
                    null, memo, actor, lines, null, reverses);
        });
    }

    private Mono<Void> validateAccounts(List<PostingLine> lines) {
        return Flux.fromIterable(lines)
                .flatMap(line -> chartAccountRepository.findById(line.accountCode())
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "No such account: " + line.accountCode()
                                        + ". The chart is fixed; adding one is a migration."))))
                .then();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value.trim();
    }

    private static long positive(long amountMinor) {
        if (amountMinor <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "amountMinor must be a positive number of rappen");
        }
        return amountMinor;
    }
}
