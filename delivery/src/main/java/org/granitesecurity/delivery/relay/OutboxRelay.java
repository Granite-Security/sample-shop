package org.granitesecurity.delivery.relay;

import org.granitesecurity.delivery.domain.DeliveryEvent;
import org.granitesecurity.delivery.repository.DeliveryEventRepository;
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

    private final DeliveryEventRepository eventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxRelay(DeliveryEventRepository eventRepository,
                       ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider) {
        this.eventRepository = eventRepository;
        this.kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:5000}")
    public void relayPendingEvents() {
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
        return eventRepository.findByStatus("PENDING")
                .flatMap(this::publishAndMarkPublished)
                .doOnError(e -> log.error("Outbox relay error", e))
                .then();
    }

    private Mono<Void> publishAndMarkPublished(DeliveryEvent event) {
        return publish(event)
                .then(Mono.defer(() -> markPublished(event)))
                .onErrorResume(e -> {
                    log.error("Failed to process event {}: {}", event.getId(), e.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> publish(DeliveryEvent event) {
        return Mono.fromFuture(kafkaTemplate.send("delivery.events", event.getAggregateId(), event.getPayload()))
                .doOnSuccess(result -> log.info("Published event {} ({}) to delivery.events",
                        event.getId(), event.getEventType()))
                .doOnError(e -> log.error("Failed to publish event {} to Kafka: {}", event.getId(), e.getMessage()))
                .then();
    }

    private Mono<Void> markPublished(DeliveryEvent event) {
        return eventRepository.markPublished(event.getId()).then();
    }
}
