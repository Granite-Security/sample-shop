package org.granitesecurity.notification.service;

import org.granitesecurity.notification.channel.Channel;
import org.granitesecurity.notification.channel.ChannelRegistry;
import org.granitesecurity.notification.channel.DeliveryResult;
import org.granitesecurity.notification.channel.RenderedMessage;
import org.granitesecurity.notification.domain.NotificationLog;
import org.granitesecurity.notification.event.EventTypes;
import org.granitesecurity.notification.event.NotificationEvent;
import org.granitesecurity.notification.repository.NotificationLogRepository;
import org.granitesecurity.notification.template.TemplateRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final TemplateRegistry templateRegistry;
    private final ChannelRegistry channelRegistry;
    private final NotificationLogRepository notificationLogRepository;
    private final boolean sendEnabled;
    private final String frontendOrigin;

    public NotificationService(TemplateRegistry templateRegistry,
                               ChannelRegistry channelRegistry,
                               NotificationLogRepository notificationLogRepository,
                               @Value("${notification.send.enabled:true}") boolean sendEnabled,
                               @Value("${notification.frontend-origin:http://localhost:5173}") String frontendOrigin) {
        this.templateRegistry = templateRegistry;
        this.channelRegistry = channelRegistry;
        this.notificationLogRepository = notificationLogRepository;
        this.sendEnabled = sendEnabled;
        this.frontendOrigin = frontendOrigin;
    }

    /**
     * Render and deliver one event. Never signals an error: a failure is recorded in
     * notification_log and swallowed, because the originating action has already
     * committed and stalling the Kafka partition would help nobody.
     */
    public Mono<DeliveryResult> handle(NotificationEvent event, String recipient) {
        if (!sendEnabled) {
            log.info("notification.send.enabled=false — skipping {} for {}", event.type(), event.username());
            return record(event, Channel.EMAIL, recipient, DeliveryResult.skippedDisabled());
        }
        if (recipient == null || recipient.isBlank()) {
            log.warn("No recipient address for {} ({}) — nothing sent", event.type(), event.username());
            return record(event, Channel.EMAIL, null, DeliveryResult.failed("no recipient address"));
        }

        Channel channel = Channel.EMAIL;
        return templateRegistry.render(event.type(), channel, recipient, model(event))
                .map(message -> deliver(event, channel, message))
                .orElseGet(() -> {
                    log.warn("No {} template for {} — nothing sent", channel, event.type());
                    return record(event, channel, recipient, DeliveryResult.failed("no template for event type"));
                });
    }

    private Mono<DeliveryResult> deliver(NotificationEvent event, Channel channel, RenderedMessage message) {
        return channelRegistry.find(channel)
                .map(target -> target.send(message)
                        .flatMap(result -> record(event, channel, message.recipient(), result)))
                .orElseGet(() -> record(event, channel, message.recipient(),
                        DeliveryResult.failed("no channel implementation for " + channel)));
    }

    private Mono<DeliveryResult> record(NotificationEvent event, Channel channel, String recipient,
                                        DeliveryResult result) {
        NotificationLog entry = new NotificationLog(
                event.id(),
                event.type(),
                channel.name(),
                recipient,
                result.status().name(),
                result.providerMessageId(),
                result.error());
        return notificationLogRepository.save(entry)
                .doOnError(e -> log.error("Failed to write notification_log for event {}", event.id(), e))
                .onErrorResume(e -> Mono.empty())
                .thenReturn(result);
    }

    /**
     * The template model. {@code raw} is included wholesale so a producer adding a
     * field needs no change here; the named entries below are derived values that a
     * template cannot compute for itself.
     */
    private Map<String, Object> model(NotificationEvent event) {
        Map<String, Object> model = new HashMap<>(event.raw());
        model.put("name", displayName(event));
        model.put("username", event.username());
        model.put("when", TIMESTAMP_FORMAT.format(event.occurredAt() == null ? Instant.now() : event.occurredAt()));
        if (EventTypes.PASSWORD_RESET_REQUESTED.equals(event.type())) {
            // The link is built here, not by auth-server: the producer publishes the
            // token as a fact and stays ignorant of frontend URL shape (D3).
            model.put("resetLink", resetLink(event));
        }
        return model;
    }

    private String resetLink(NotificationEvent event) {
        Object token = event.raw().get("resetToken");
        return frontendOrigin + "/reset-password/confirm?token=" + (token == null ? "" : token);
    }

    private static String displayName(NotificationEvent event) {
        String username = event.username();
        return username == null || username.isBlank() ? "there" : username;
    }
}
