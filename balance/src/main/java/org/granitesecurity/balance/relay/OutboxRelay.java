package org.granitesecurity.balance.relay;

import org.granitesecurity.balance.domain.OutboxEvent;
import org.granitesecurity.balance.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Ships committed outbox rows to {@code balance.events} (docs/finance/accounting.md §4.2).
 *
 * <p>Copied from payment's relay, with one change: rows are published <b>in order, one at
 * a time</b>, not with a concurrent {@code flatMap}. balance keys its messages by username,
 * so a user's facts share a partition and arrive in order — but only if they are sent in
 * order. A {@code Refunded} overtaking the {@code Spent} it reverses would leave the books
 * reversing a sale they have not booked.
 *
 * <p>A separate scheduled read, so this does not violate "no {@code .block()} in balance"
 * (finance.md §7.2): nothing here runs inside a movement's transaction.
 *
 * <p><b>At-least-once, by design.</b> A crash between the send and the {@code markSent}
 * republishes the row on the next poll. That is the correct trade: the alternative loses
 * the fact entirely, and a duplicate is something a consumer can handle — accounting keys
 * on {@code transferId} and inserts a {@code processed_event} row before it posts anything.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    /** Bounds one poll, so a long backlog cannot hold the scheduler thread indefinitely. */
    @Value("${app.outbox.batch-size:200}")
    private int batchSize;

    public OutboxRelay(OutboxRepository outboxRepository,
                       ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:5000}")
    void run() {
        if (kafkaTemplate == null) {
            return;
        }
        publishPending().subscribe();
    }

    Mono<Void> publishPending() {
        if (kafkaTemplate == null) {
            log.warn("KafkaTemplate not available — skipping outbox relay");
            return Mono.empty();
        }
        return outboxRepository.findPending(batchSize)
                // concatMap, not flatMap: see the ordering note on the class.
                .concatMap(this::publishAndMarkSent)
                .then()
                // The stream above stops at the first failure; this keeps the scheduled
                // run itself from ending in an unhandled error.
                .onErrorResume(e -> {
                    log.error("Outbox relay stopped early; pending rows retry next poll", e);
                    return Mono.empty();
                });
    }

    /**
     * Deliberately does <b>not</b> swallow the error, unlike payment's relay. Skipping a
     * failed row and carrying on would publish the next one — possibly the same user's
     * next fact — while its predecessor is still PENDING, which is exactly the reordering
     * this relay exists to prevent. The error propagates, the poll stops, and every
     * remaining row is retried in order on the next one.
     */
    private Mono<Void> publishAndMarkSent(OutboxEvent event) {
        return publish(event)
                .then(Mono.defer(() -> markSent(event)))
                .doOnError(e -> log.error("Failed to publish outbox event {} ({}): {}",
                        event.getId(), event.getEventType(), e.getMessage()));
    }

    private Mono<Void> publish(OutboxEvent event) {
        return Mono.fromFuture(kafkaTemplate.send(
                        OutboxEvent.TOPIC, event.getAggregateId(), event.getPayload()))
                .doOnSuccess(result -> log.info("Published {} ({}) to {} key={}",
                        event.getId(), event.getEventType(), OutboxEvent.TOPIC, event.getAggregateId()))
                .then();
    }

    private Mono<Void> markSent(OutboxEvent event) {
        return outboxRepository.markSent(event.getId()).then();
    }
}
