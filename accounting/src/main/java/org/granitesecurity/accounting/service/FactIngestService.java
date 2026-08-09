package org.granitesecurity.accounting.service;

import org.granitesecurity.accounting.domain.Fact;
import org.granitesecurity.accounting.domain.Journal;
import org.granitesecurity.accounting.domain.ProcessedEvent;
import org.granitesecurity.accounting.repository.FactRepository;
import org.granitesecurity.accounting.repository.ProcessedEventRepository;
import org.granitesecurity.accounting.service.PostingOutcome.Ignore;
import org.granitesecurity.accounting.service.PostingOutcome.Post;
import org.granitesecurity.accounting.service.PostingOutcome.Wait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Store the fact, then derive the journal (docs/finance/accounting.md §6).
 *
 * <p><b>All of it in one transaction</b>, which is where this departs from notification's
 * otherwise-identical idempotency. notification inserts {@code processed_event} before
 * sending because it cannot transact with an SMTP server, and accepts that a crash in
 * between loses the mail. Here a crash in between would lose a <em>fact</em>, permanently,
 * from a ledger — so the marker, the fact and the journal commit together or not at all,
 * and a crash replays the whole thing. The unique key still makes redelivery a no-op.
 *
 * <p><b>No staleness rule</b> (D23). A fact older than any threshold is still a fact.
 */
@Service
public class FactIngestService {

    private static final Logger log = LoggerFactory.getLogger(FactIngestService.class);

    private final FactRepository factRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final PostingRules postingRules;
    private final PeriodService periodService;
    private final JournalService journalService;

    public FactIngestService(FactRepository factRepository,
                             ProcessedEventRepository processedEventRepository,
                             PostingRules postingRules,
                             PeriodService periodService,
                             JournalService journalService) {
        this.factRepository = factRepository;
        this.processedEventRepository = processedEventRepository;
        this.postingRules = postingRules;
        this.periodService = periodService;
        this.journalService = journalService;
    }

    @Transactional
    public Mono<Void> ingest(IncomingEvent incoming) {
        ProcessedEvent marker = new ProcessedEvent();
        marker.setEventKey(incoming.eventKey());
        marker.setTopic(incoming.topic());
        marker.setEventType(incoming.eventType());
        marker.setProcessedAt(Instant.now());

        // Checked inside the transaction rather than caught after the fact: a duplicate
        // INSERT aborts the transaction at the database, and catching it here would then
        // try to commit a transaction Postgres has already given up on. A genuine race
        // between two consumers still raises the unique violation, which propagates, and
        // Kafka's redelivery finds the marker on the second pass. Redelivery is normal —
        // at-least-once is the contract — and the right response is silence, not a second
        // journal.
        return processedEventRepository.existsById(incoming.eventKey())
                .flatMap(seen -> {
                    if (Boolean.TRUE.equals(seen)) {
                        log.debug("Event {} already processed, skipping", incoming.eventKey());
                        return Mono.<Void>empty();
                    }
                    return processedEventRepository.save(marker)
                            .then(Mono.defer(() -> store(incoming)))
                            .flatMap(this::postIfPossible);
                })
                .then();
    }

    private Mono<Fact> store(IncomingEvent incoming) {
        Fact fact = new Fact();
        fact.setTopic(incoming.topic());
        fact.setEventType(incoming.eventType());
        fact.setEventKey(incoming.eventKey());
        fact.setAggregateId(incoming.aggregateId());
        fact.setOccurredAt(incoming.occurredAt());
        fact.setReceivedAt(Instant.now());
        fact.setPayload(incoming.payload());
        fact.setStatus(Fact.UNPOSTED);
        return factRepository.save(fact);
    }

    /**
     * Attempts the journal. A fact that cannot post yet stays UNPOSTED and is swept later;
     * one that will never post is IGNORED with the reason, so "why is there no entry for
     * this?" always has an answer.
     */
    @Transactional
    public Mono<Void> postIfPossible(Fact fact) {
        if (periodService.isBeforeBooksOpen(fact.getOccurredAt())) {
            // Everything before the opening balance is cash view only (D22). Booking it
            // would be reconstructing a past that was never booked.
            return factRepository.markIgnored(fact.getId(), "before the books opened").then();
        }
        return postingRules.derive(fact)
                .flatMap(outcome -> switch (outcome) {
                    case Ignore ignore -> factRepository.markIgnored(fact.getId(), ignore.reason()).then();
                    case Wait wait -> factRepository
                            .recordAttempt(fact.getId(), "waiting for " + wait.waitingFor()).then();
                    case Post post -> write(fact, post);
                })
                .onErrorResume(e -> {
                    // Left UNPOSTED on purpose: a rule that threw is a bug to fix, and the
                    // fact must still be sitting there waiting when it is.
                    log.error("Could not post fact {} ({}): {}",
                            fact.getId(), fact.getEventType(), e.getMessage(), e);
                    return Mono.error(e);
                });
    }

    private Mono<Void> write(Fact fact, Post post) {
        return periodService.placeFor(fact.getOccurredAt())
                .flatMap(placement -> journalService.post(
                                placement.periodCode(),
                                placement.priorPeriod(),
                                fact.getOccurredAt(),
                                Journal.SOURCE_EVENT,
                                fact.getEventType(),
                                fact.getAggregateId(),
                                post.memo(),
                                null,
                                post.lines())
                        .flatMap(journal -> factRepository.markPosted(fact.getId(), journal.getId())
                                .doOnSuccess(v -> log.info("Posted {} for {} into {}{}",
                                        fact.getEventType(), fact.getAggregateId(), placement.periodCode(),
                                        placement.priorPeriod() ? " (prior-period adjustment)" : ""))))
                .then();
    }

    /**
     * One event, normalised. Producers disagree about how to name an event — shop tags a
     * type, payment carries only a status, delivery carries both and means the status —
     * so the consumers translate once, here, and everything downstream sees one shape.
     */
    public record IncomingEvent(String topic, String eventType, String eventKey,
                                String aggregateId, Instant occurredAt, String payload) {}
}
