package org.granitesecurity.shop.relay;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.granitesecurity.shop.domain.OutboxEvent;
import org.granitesecurity.shop.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        ObjectProvider<KafkaTemplate<String, String>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(kafkaTemplate);
        relay = new OutboxRelay(outboxRepository, provider);
    }

    @Test
    void publishesPendingEventsAndMarksSent() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = outboxEvent(eventId, "order", "42", "OrderPlaced", "{\"orderId\":42}");

        when(outboxRepository.findByStatusOrderByCreatedAtAsc("PENDING"))
                .thenReturn(Flux.just(event));
        when(kafkaTemplate.send(eq("orders.events"), eq("42"), eq("{\"orderId\":42}")))
                .thenReturn(CompletableFuture.completedFuture(sendResult("orders.events", "42", "{\"orderId\":42}")));
        when(outboxRepository.markSent(eventId))
                .thenReturn(Mono.just(1));

        StepVerifier.create(relay.publishPending())
                .verifyComplete();

        verify(outboxRepository).findByStatusOrderByCreatedAtAsc("PENDING");
        verify(kafkaTemplate).send("orders.events", "42", "{\"orderId\":42}");
        verify(outboxRepository).markSent(eventId);
    }

    @Test
    void skipsEventWhenKafkaFailsAndContinuesToNext() {
        OutboxEvent goodEvent = outboxEvent(UUID.randomUUID(), "order", "1", "OrderPlaced", "{\"orderId\":1}");
        OutboxEvent badEvent = outboxEvent(UUID.randomUUID(), "order", "2", "OrderPlaced", "{\"orderId\":2}");

        when(outboxRepository.findByStatusOrderByCreatedAtAsc("PENDING"))
                .thenReturn(Flux.just(goodEvent, badEvent));
        when(kafkaTemplate.send(eq("orders.events"), eq("1"), any()))
                .thenReturn(CompletableFuture.completedFuture(sendResult("orders.events", "1", "{}")));
        when(kafkaTemplate.send(eq("orders.events"), eq("2"), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka unavailable")));
        when(outboxRepository.markSent(goodEvent.getId()))
                .thenReturn(Mono.just(1));

        StepVerifier.create(relay.publishPending())
                .verifyComplete();

        verify(outboxRepository).markSent(goodEvent.getId());
        verify(outboxRepository, never()).markSent(badEvent.getId());
    }

    @Test
    void doesNothingWhenNoPendingEvents() {
        when(outboxRepository.findByStatusOrderByCreatedAtAsc("PENDING"))
                .thenReturn(Flux.empty());

        StepVerifier.create(relay.publishPending())
                .verifyComplete();

        verify(kafkaTemplate, never()).send(any(), any(), any());
        verify(outboxRepository, never()).markSent(any());
    }

    @Test
    void sendsWithCorrectTopicAndKey() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = outboxEvent(eventId, "order", "99", "OrderPlaced", "{\"orderId\":99}");

        when(outboxRepository.findByStatusOrderByCreatedAtAsc("PENDING"))
                .thenReturn(Flux.just(event));
        when(kafkaTemplate.send(eq("orders.events"), eq("99"), any()))
                .thenReturn(CompletableFuture.completedFuture(sendResult("orders.events", "99", "{}")));
        when(outboxRepository.markSent(eventId))
                .thenReturn(Mono.just(1));

        StepVerifier.create(relay.publishPending())
                .verifyComplete();

        verify(kafkaTemplate).send("orders.events", "99", "{\"orderId\":99}");
    }

    @Test
    void publishesMultipleEventsInOrder() {
        OutboxEvent first = outboxEvent(UUID.randomUUID(), "order", "1", "OrderPlaced", "{\"orderId\":1}");
        OutboxEvent second = outboxEvent(UUID.randomUUID(), "order", "2", "OrderPlaced", "{\"orderId\":2}");

        when(outboxRepository.findByStatusOrderByCreatedAtAsc("PENDING"))
                .thenReturn(Flux.just(first, second));
        when(kafkaTemplate.send(eq("orders.events"), eq("1"), any()))
                .thenReturn(CompletableFuture.completedFuture(sendResult("orders.events", "1", "{}")));
        when(kafkaTemplate.send(eq("orders.events"), eq("2"), any()))
                .thenReturn(CompletableFuture.completedFuture(sendResult("orders.events", "2", "{}")));
        when(outboxRepository.markSent(first.getId())).thenReturn(Mono.just(1));
        when(outboxRepository.markSent(second.getId())).thenReturn(Mono.just(1));

        StepVerifier.create(relay.publishPending())
                .verifyComplete();

        verify(kafkaTemplate).send("orders.events", "1", "{\"orderId\":1}");
        verify(kafkaTemplate).send("orders.events", "2", "{\"orderId\":2}");
        verify(outboxRepository).markSent(first.getId());
        verify(outboxRepository).markSent(second.getId());
    }

    @Test
    void markSentNotCalledWhenPublishFails() {
        OutboxEvent event = outboxEvent(UUID.randomUUID(), "order", "1", "OrderPlaced", "{}");

        when(outboxRepository.findByStatusOrderByCreatedAtAsc("PENDING"))
                .thenReturn(Flux.just(event));
        when(kafkaTemplate.send(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Broker unavailable")));

        StepVerifier.create(relay.publishPending())
                .verifyComplete();

        verify(outboxRepository, never()).markSent(any());
    }

    private static OutboxEvent outboxEvent(UUID id, String aggregateType, String aggregateId,
                                            String eventType, String payload) {
        OutboxEvent event = new OutboxEvent();
        event.setId(id);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setPayload(payload);
        event.setStatus("PENDING");
        event.setCreatedAt(Instant.now());
        return event;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> SendResult<K, V> sendResult(String topic, K key, V value) {
        ProducerRecord<K, V> record = new ProducerRecord<>(topic, key, value);
        RecordMetadata metadata = mock(RecordMetadata.class, withSettings().stubOnly());
        return new SendResult<>(record, metadata);
    }
}
