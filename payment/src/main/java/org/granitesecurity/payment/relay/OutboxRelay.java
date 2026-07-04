package org.granitesecurity.payment.relay;

import org.granitesecurity.payment.domain.OutboxEvent;
import org.granitesecurity.payment.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

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
        return outboxRepository.findByStatusOrderByCreatedAtAsc("PENDING")
                .flatMap(this::publishAndMarkSent)
                .doOnError(e -> log.error("Outbox relay error", e))
                .then();
    }

    private Mono<Void> publishAndMarkSent(OutboxEvent event) {
        return publish(event)
                .then(Mono.defer(() -> markSent(event)))
                .onErrorResume(e -> {
                    log.error("Failed to process event {}: {}", event.getId(), e.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> publish(OutboxEvent event) {
        return Mono.fromFuture(kafkaTemplate.send("payments.events", event.getAggregateId(), event.getPayload()))
                .doOnSuccess(result -> log.info("Published event {} ({}) to payments.events", event.getId(), event.getEventType()))
                .doOnError(e -> log.error("Failed to publish event {} to Kafka: {}", event.getId(), e.getMessage()))
                .then();
    }

    private Mono<Void> markSent(OutboxEvent event) {
        return outboxRepository.markSent(event.getId())
                .doOnSuccess(count -> {
                    if (count != null && count > 0) {
                        log.debug("Marked event {} as SENT", event.getId());
                    }
                })
                .then();
    }
}
