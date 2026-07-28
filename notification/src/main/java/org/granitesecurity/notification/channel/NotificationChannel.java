package org.granitesecurity.notification.channel;

import reactor.core.publisher.Mono;

/**
 * A delivery transport. Adding SMS (Twilio), WhatsApp or push means adding an
 * implementation and a set of templates — no producer, event or routing change.
 *
 * <p>Implementations must never signal an error: they return a
 * {@link DeliveryResult} describing what happened, so one failing provider cannot
 * abort a batch or stall a Kafka partition.
 */
public interface NotificationChannel {

    Channel channel();

    Mono<DeliveryResult> send(RenderedMessage message);
}
