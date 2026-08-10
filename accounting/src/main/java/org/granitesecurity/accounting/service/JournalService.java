package org.granitesecurity.accounting.service;

import org.granitesecurity.accounting.domain.Journal;
import org.granitesecurity.accounting.domain.JournalLine;
import org.granitesecurity.accounting.repository.JournalLineRepository;
import org.granitesecurity.accounting.repository.JournalRepository;
import org.granitesecurity.accounting.service.PostingOutcome.PostingLine;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Writes journals. The only thing in this service that creates one.
 *
 * <p>Balance is asserted here as well as by the database's deferred constraint trigger.
 * Not redundant: the trigger fails the transaction with a message about a UUID, while
 * this fails with the rule that was wrong — and a posting rule that does not balance is
 * a bug in {@link PostingRules}, not a database error.
 */
@Service
public class JournalService {

    private final JournalRepository journalRepository;
    private final JournalLineRepository journalLineRepository;

    public JournalService(JournalRepository journalRepository,
                          JournalLineRepository journalLineRepository) {
        this.journalRepository = journalRepository;
        this.journalLineRepository = journalLineRepository;
    }

    public Mono<Journal> post(String periodCode, boolean priorPeriod, Instant occurredAt,
                              String source, String eventType, String reference, String memo,
                              String createdBy, List<PostingLine> lines) {
        return post(periodCode, priorPeriod, occurredAt, source, eventType, reference, memo,
                createdBy, lines, null);
    }

    /**
     * @param assumptions the rate set an estimate was made under, or null for a measured
     *                    entry. Set here, at insert: journals are append-only in the
     *                    database, so there is no marking one as an estimate afterwards —
     *                    which is the point. An entry states what it was when it was made.
     */
    public Mono<Journal> post(String periodCode, boolean priorPeriod, Instant occurredAt,
                              String source, String eventType, String reference, String memo,
                              String createdBy, List<PostingLine> lines, String assumptions) {
        return post(periodCode, priorPeriod, occurredAt, source, eventType, reference, memo,
                createdBy, lines, assumptions, null);
    }

    /**
     * @param reversesId the entry this one corrects. Set at insert, like everything else:
     *                   a posted journal cannot be updated, so a reversal that did not know
     *                   what it reversed at the moment it was written never could
     */
    public Mono<Journal> post(String periodCode, boolean priorPeriod, Instant occurredAt,
                              String source, String eventType, String reference, String memo,
                              String createdBy, List<PostingLine> lines, String assumptions,
                              UUID reversesId) {
        long debits = lines.stream().mapToLong(PostingLine::debitMinor).sum();
        long credits = lines.stream().mapToLong(PostingLine::creditMinor).sum();
        if (lines.isEmpty()) {
            return Mono.error(new IllegalStateException("Refusing to post a journal with no lines"));
        }
        if (debits != credits) {
            return Mono.error(new IllegalStateException(
                    "Posting rule for " + eventType + " does not balance: debits " + debits
                            + " != credits " + credits));
        }

        Journal journal = new Journal();
        journal.setId(UUID.randomUUID());
        journal.setPeriodCode(periodCode);
        journal.setPriorPeriod(priorPeriod);
        journal.setOccurredAt(occurredAt);
        journal.setPostedAt(Instant.now());
        journal.setSource(source);
        journal.setEventType(eventType);
        journal.setReference(reference);
        journal.setMemo(memo);
        journal.setCreatedBy(createdBy);
        journal.setEstimated(assumptions != null);
        journal.setAssumptions(assumptions);
        journal.setReversesId(reversesId);

        return journalRepository.save(journal)
                .flatMap(saved -> Flux.fromIterable(lines)
                        .concatMap(line -> journalLineRepository.save(toLine(saved.getId(), line)))
                        .then(Mono.just(saved)));
    }

    private static JournalLine toLine(UUID journalId, PostingLine line) {
        JournalLine entity = new JournalLine();
        entity.setJournalId(journalId);
        entity.setAccountCode(line.accountCode());
        entity.setDebitMinor(line.debitMinor());
        entity.setCreditMinor(line.creditMinor());
        entity.setParty(line.party());
        entity.setMemo(line.memo());
        return entity;
    }
}
