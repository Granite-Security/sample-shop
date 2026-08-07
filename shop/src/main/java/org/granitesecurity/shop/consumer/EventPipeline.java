package org.granitesecurity.shop.consumer;

import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.util.retry.Retry;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Runs shop's Kafka consumers as reactive streams, replacing {@code @KafkaListener}.
 *
 * <p>Why not the annotation: it is a blocking, thread-per-record contract, so every
 * handler had to {@code block()} on an R2DBC call — a synchronous island in a service
 * that is otherwise non-blocking end to end. More importantly, the offset was committed
 * whenever the method returned, which made "log the exception and carry on" indistinguishable
 * from success.
 *
 * <p>What this guarantees instead:
 * <ul>
 *   <li><b>Offsets follow the work.</b> A record is acknowledged only after its handler
 *       completes, or after it has been safely parked on the dead-letter topic. A failure
 *       to do either leaves the offset uncommitted and the record redelivered.</li>
 *   <li><b>Transient and permanent failures are told apart.</b> A slow database is retried
 *       with exponential backoff; a {@link MalformedEventException} is dead-lettered at once,
 *       because reparsing it a fifth time will not help.</li>
 *   <li><b>Order is preserved per partition.</b> Records are grouped by partition and run
 *       with {@code concatMap}, so two events for the same order cannot overtake each other,
 *       while separate partitions still progress in parallel — something the annotation's
 *       concurrency setting cannot offer.</li>
 * </ul>
 *
 * <p>Redelivery is safe: {@code OrderStatus} rejects illegal transitions and applying the
 * same one twice is a no-op, so at-least-once delivery does not corrupt an order.
 */
@Component
public class EventPipeline {

    private static final Logger log = LoggerFactory.getLogger(EventPipeline.class);

    /** Suffix matching Spring Kafka's DeadLetterPublishingRecoverer, so tooling agrees. */
    private static final String DLT_SUFFIX = ".DLT";

    private static final int MAX_ATTEMPTS = 4;
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final EventConsumer eventConsumer;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String bootstrapServers;
    private final List<Disposable> subscriptions = new CopyOnWriteArrayList<>();

    public EventPipeline(EventConsumer eventConsumer,
                         KafkaTemplate<String, String> kafkaTemplate,
                         @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        this.eventConsumer = eventConsumer;
        this.kafkaTemplate = kafkaTemplate;
        this.bootstrapServers = bootstrapServers;
    }

    /**
     * Started once the context is up rather than in a constructor or {@code @PostConstruct}:
     * a record could otherwise arrive before the repositories it needs are ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        subscribe("payments.events", "shop.payments.events.consumer", eventConsumer::onPaymentEvent);
        subscribe("shipments.events", "shop.shipments.events.consumer", eventConsumer::onShipmentEvent);
        subscribe("delivery.events", "shop.delivery.events.consumer", eventConsumer::onDeliveryEvent);
    }

    @PreDestroy
    public void stop() {
        subscriptions.forEach(Disposable::dispose);
        subscriptions.clear();
    }

    private void subscribe(String topic, String groupId, Function<String, Mono<Void>> handler) {
        Disposable subscription = receive(topic, groupId)
                .groupBy(record -> record.receiverOffset().topicPartition())
                // concatMap within a partition keeps per-key order; flatMap across
                // partitions lets them advance independently.
                .flatMap(partition -> partition.concatMap(record -> handle(record, topic, handler)))
                // The stream only errors when the broker connection or an acknowledgement
                // does — per-record failures never reach here. Resubscribing rewinds to the
                // last committed offset, so nothing is skipped by the restart.
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(2)).maxBackoff(MAX_BACKOFF)
                        .doBeforeRetry(signal -> log.error("{} consumer failed, resubscribing: {}",
                                topic, signal.failure().getMessage())))
                .subscribe(null, error -> log.error("{} consumer stopped permanently", topic, error));
        subscriptions.add(subscription);
    }

    private Flux<ReceiverRecord<String, String>> receive(String topic, String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        ReceiverOptions<String, String> options = ReceiverOptions.<String, String>create(props)
                .subscription(List.of(topic));
        return KafkaReceiver.create(options).receive();
    }

    private Mono<Void> handle(ReceiverRecord<String, String> record,
                              String topic,
                              Function<String, Mono<Void>> handler) {
        return Mono.defer(() -> handler.apply(record.value()))
                .retryWhen(Retry.backoff(MAX_ATTEMPTS, FIRST_BACKOFF)
                        .maxBackoff(MAX_BACKOFF)
                        // A message that cannot be parsed will not parse later either.
                        .filter(error -> !(error instanceof MalformedEventException))
                        .doBeforeRetry(signal -> log.warn("Retrying {} offset {} after {}: {}",
                                topic, record.offset(), signal.totalRetries() + 1,
                                signal.failure().getMessage())))
                .onErrorResume(error -> deadLetter(record, topic, error))
                // Reached only on success or after the record is parked: acknowledging in
                // doFinally would commit failures too, which is the bug this replaces.
                .then(Mono.fromRunnable(() -> record.receiverOffset().acknowledge()));
    }

    /**
     * Parks a record that cannot be processed, so it is visible and replayable rather than
     * lost to a log line.
     *
     * <p>If publishing itself fails the error is propagated: the offset stays uncommitted,
     * the stream resubscribes, and the record comes back. Dropping it here would reintroduce
     * exactly the silent loss this class exists to prevent.
     */
    private Mono<Void> deadLetter(ReceiverRecord<String, String> record, String topic, Throwable error) {
        String dltTopic = topic + DLT_SUFFIX;
        ProducerRecord<String, String> dead = new ProducerRecord<>(dltTopic, record.key(), record.value());
        dead.headers()
                .add("x-original-topic", topic.getBytes(StandardCharsets.UTF_8))
                .add("x-original-partition", String.valueOf(record.partition()).getBytes(StandardCharsets.UTF_8))
                .add("x-original-offset", String.valueOf(record.offset()).getBytes(StandardCharsets.UTF_8))
                .add("x-exception", String.valueOf(error).getBytes(StandardCharsets.UTF_8));

        log.error("Dead-lettering {} offset {} to {}: {}", topic, record.offset(), dltTopic, error.toString());
        return Mono.fromFuture(kafkaTemplate.send(dead))
                .doOnError(publishError -> log.error(
                        "Failed to dead-letter {} offset {} — offset stays uncommitted, record will return",
                        topic, record.offset(), publishError))
                .then();
    }
}
