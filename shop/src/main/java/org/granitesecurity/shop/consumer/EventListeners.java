package org.granitesecurity.shop.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The Kafka entry points: adapters, and nothing else.
 *
 * <p>Each method blocks on the {@link EventConsumer} handler it delegates to, and that is
 * deliberate. {@code @KafkaListener} is a blocking contract — the container decides whether
 * to commit the offset from how this method returns, so it has to wait for the work. The
 * blocking happens on a listener thread, never on the event loop, so no request path is
 * affected.
 *
 * <p>A reactive receiver would remove even that, and was tried: {@code reactor-kafka} 1.3.23
 * is the newest release and is compiled against kafka-clients 3.x, whose {@code ConsumerRecord}
 * constructor 4.x removed. Every consumer died with {@code NoSuchMethodError} on its first
 * record in production. Revisit only when a reactor-kafka release supports kafka-clients 4.
 *
 * <p><b>Nothing here catches an exception.</b> That is the point: a failure must reach the
 * container so the offset is not committed and the record is retried, and ultimately
 * dead-lettered by the error handler in {@code KafkaConfig}. Catching and logging — which is
 * what this code used to do — committed the offset and lost the transition while the money
 * had already moved.
 */
@Component
public class EventListeners {

    private static final Logger log = LoggerFactory.getLogger(EventListeners.class);

    private final EventConsumer eventConsumer;

    public EventListeners(EventConsumer eventConsumer) {
        this.eventConsumer = eventConsumer;
    }

    @KafkaListener(topics = "payments.events", groupId = "shop.payments.events.consumer")
    void onPaymentEvent(String message) {
        eventConsumer.onPaymentEvent(message).block();
    }

    @KafkaListener(topics = "shipments.events", groupId = "shop.shipments.events.consumer")
    void onShipmentEvent(String message) {
        eventConsumer.onShipmentEvent(message).block();
    }

    @KafkaListener(topics = "delivery.events", groupId = "shop.delivery.events.consumer")
    void onDeliveryEvent(String message) {
        eventConsumer.onDeliveryEvent(message).block();
    }
}
