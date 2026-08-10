package org.granitesecurity.accounting.service;

import org.granitesecurity.accounting.domain.Journal;
import org.granitesecurity.accounting.repository.JournalRepository;
import org.granitesecurity.accounting.service.PostingOutcome.PostingLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The books open with a stated position (docs/finance/accounting.md §14.2, D22, D33).
 *
 * <p>Kafka retention has already deleted the history, so there is nothing to replay and no
 * honest way to reconstruct a past that was never booked. The answer is the normal one: one
 * journal on day zero, stating where we start.
 *
 * <p><b>The figures are stated, not fetched.</b> This service does not ask shop for its
 * inventory or balance for its liabilities (D25) — and beyond that rule, an opening balance
 * is a declaration someone is accountable for, not a number scraped from two databases at
 * whatever moment the job ran.
 *
 * <p>Three of them need care, and §14.2 explains why:
 * <ul>
 *   <li><b>Stored value is the backed portion only.</b> Policy (b) books no liability for
 *       gifted credit, so including it would recognise an obligation the accounting policy
 *       says does not exist.</li>
 *   <li><b>Deferred revenue</b> is orders paid but not yet delivered — money held for goods
 *       still owed.</li>
 *   <li><b>Owner's capital is a plug</b> and is labelled one. It is not a measured
 *       contribution of capital; it is whatever makes the entry balance. Rendering it as
 *       "Owner's capital" without that caveat is what makes a whole statement
 *       untrustworthy.</li>
 * </ul>
 */
@Service
public class OpeningBalanceService {

    private static final Logger log = LoggerFactory.getLogger(OpeningBalanceService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String EVENT_TYPE = "OpeningBalance";

    private final JournalRepository journalRepository;
    private final JournalService journalService;
    private final PeriodService periodService;

    public OpeningBalanceService(JournalRepository journalRepository,
                                 JournalService journalService,
                                 PeriodService periodService) {
        this.journalRepository = journalRepository;
        this.journalService = journalService;
        this.periodService = periodService;
    }

    /**
     * Posted <b>once</b>, on the date the books open. Not idempotent-and-overwriting:
     * a second opening balance is not a correction, it is a second past. If the first one
     * was wrong, correct it the way everything else here is corrected — by reversal.
     */
    @Transactional
    public Mono<Journal> post(OpeningPosition position, String actor) {
        return journalRepository.countBySource(Journal.SOURCE_OPENING).flatMap(existing -> {
            if (existing > 0) {
                return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                        "The books already have an opening balance. Correct it by reversal, "
                                + "not by posting a second one."));
            }
            long debits = position.bankMinor() + position.equipmentMinor()
                    + position.inventoryMinor() + position.receivablesMinor();
            long statedCredits = position.storedValueBackedMinor() + position.deferredRevenueMinor();
            long plug = debits - statedCredits;

            List<PostingLine> lines = new ArrayList<>();
            add(lines, PostingLine.debit(Accounts.CASH, position.bankMinor()));
            add(lines, PostingLine.debit(Accounts.EQUIPMENT, position.equipmentMinor()));
            add(lines, PostingLine.debit(Accounts.INVENTORY, position.inventoryMinor()));
            add(lines, PostingLine.debit(Accounts.RECEIVABLES, position.receivablesMinor()));
            add(lines, PostingLine.credit(Accounts.STORED_VALUE, position.storedValueBackedMinor()));
            add(lines, PostingLine.credit(Accounts.DEFERRED_REVENUE, position.deferredRevenueMinor()));
            if (plug > 0) {
                add(lines, PostingLine.credit(Accounts.OWNERS_CAPITAL, plug));
            } else if (plug < 0) {
                // Liabilities exceed assets on day one: the plug is a debit, i.e. the
                // owner is owed nothing and the books open with accumulated losses. Rare,
                // and worth seeing rather than hiding behind an absolute value.
                add(lines, PostingLine.debit(Accounts.OWNERS_CAPITAL, -plug));
            }
            if (lines.isEmpty()) {
                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "An opening balance of nothing is not an opening balance"));
            }

            Instant occurredAt = periodService.booksOpenOn()
                    .atStartOfDay(PeriodService.ZURICH).toInstant();

            Map<String, Object> assumptions = new LinkedHashMap<>();
            assumptions.put("basis", "stated opening position, not a reconstructed past (D22)");
            assumptions.put("statedBy", actor);
            assumptions.put("ownersCapitalIsAPlug", true);
            assumptions.put("plugMinor", plug);
            assumptions.put("note", "3000 is whatever makes this entry balance — not a measured "
                    + "contribution of capital");
            assumptions.put("storedValueNote", "backed portion only; policy (b) books no liability "
                    + "for gifted credit (§2.4)");

            return periodService.placeFor(occurredAt).flatMap(placement ->
                    journalService.post(placement.periodCode(), placement.priorPeriod(), occurredAt,
                                    Journal.SOURCE_OPENING, EVENT_TYPE, null,
                                    "Opening balance on " + periodService.booksOpenOn(), actor,
                                    lines, MAPPER.writeValueAsString(assumptions)))
                    .doOnSuccess(j -> log.info("Posted opening balance; owner's capital plug {} rappen", plug));
        });
    }

    private static void add(List<PostingLine> lines, PostingLine line) {
        if (line.debitMinor() > 0 || line.creditMinor() > 0) {
            lines.add(line);
        }
    }

    /**
     * @param storedValueBackedMinor Σ(positive balances) − Σ(gift pools). The gifted part is
     *                               deliberately excluded (§14.2)
     * @param deferredRevenueMinor   orders PAID but not yet DELIVERED
     */
    public record OpeningPosition(long bankMinor, long equipmentMinor, long inventoryMinor,
                                  long receivablesMinor, long storedValueBackedMinor,
                                  long deferredRevenueMinor) {}
}
