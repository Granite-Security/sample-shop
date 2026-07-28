package org.granitesecurity.notification.consumer;

import org.granitesecurity.notification.domain.NotificationLog;
import org.granitesecurity.notification.domain.ProcessedEvent;
import org.granitesecurity.notification.event.EventTypes;
import org.granitesecurity.notification.event.NotificationEvent;
import org.granitesecurity.notification.repository.NotificationLogRepository;
import org.granitesecurity.notification.repository.ProcessedEventRepository;
import org.granitesecurity.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Consumes identity events produced by auth-server.
 *
 * <p>Order of operations matters: staleness is checked first (a stale event is not a
 * failure and must not be retried), then the dedupe row is inserted, and only then is
 * anything sent. Kafka delivery is at-least-once, so duplicates are guaranteed rather
 * than hypothetical, and sending a password-reset email twice is user-visible.
 */
@Component
public class IdentityEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(IdentityEventConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NotificationService notificationService;
    private final ProcessedEventRepository processedEventRepository;
    private final NotificationLogRepository notificationLogRepository;

    public IdentityEventConsumer(NotificationService notificationService,
                                 ProcessedEventRepository processedEventRepository,
                                 NotificationLogRepository notificationLogRepository) {
        this.notificationService = notificationService;
        this.processedEventRepository = processedEventRepository;
        this.notificationLogRepository = notificationLogRepository;
    }

    @KafkaListener(topics = "notifications.events", groupId = "notification.notifications.events.consumer")
    public void consume(String message) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = MAPPER.readValue(message, Map.class);
            NotificationEvent event = NotificationEvent.from(data, message);
            process(event).block();
        } catch (Exception e) {
            // Swallowed deliberately: a malformed message would otherwise be redelivered
            // forever and block the partition for every well-formed event behind it.
            log.error("Failed to process identity event: {}", message, e);
        }
    }

    private Mono<Void> process(NotificationEvent event) {
        Duration age = Duration.between(event.occurredAt(), Instant.now());
        Duration maxAge = EventTypes.maxAge(event.type());
        if (age.compareTo(maxAge) > 0) {
            log.warn("Dropping stale {} for {} — {}s old, max {}s",
                    event.type(), event.username(), age.toSeconds(), maxAge.toSeconds());
            return recordDroppedStale(event).then();
        }

        return processedEventRepository.save(new ProcessedEvent(event.id(), event.type()))
                .flatMap(saved -> notificationService.handle(event, event.email()))
                .doOnNext(result -> log.info("Handled {} for {}: {}",
                        event.type(), event.username(), result.status()))
                .onErrorResume(IdentityEventConsumer::isDuplicate, e -> {
                    log.info("Event {} already processed — skipping", event.id());
                    return Mono.empty();
                })
                .then();
    }

    private static boolean isDuplicate(Throwable e) {
        return e instanceof DuplicateKeyException || e instanceof DataIntegrityViolationException;
    }

    private Mono<NotificationLog> recordDroppedStale(NotificationEvent event) {
        return notificationLogRepository.save(new NotificationLog(
                        event.id(), event.type(), "EMAIL", event.email(), "DROPPED_STALE", null, null))
                .onErrorResume(e -> {
                    log.error("Failed to record DROPPED_STALE for event {}", event.id(), e);
                    return Mono.empty();
                });
    }
}
