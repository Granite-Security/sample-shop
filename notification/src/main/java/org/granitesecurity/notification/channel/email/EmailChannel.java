package org.granitesecurity.notification.channel.email;

import org.granitesecurity.notification.channel.Channel;
import org.granitesecurity.notification.channel.DeliveryResult;
import org.granitesecurity.notification.channel.NotificationChannel;
import org.granitesecurity.notification.channel.RenderedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Email delivery via Resend. Carries over the behaviour of profile's EmailService:
 * disabled (log and skip) when the API key is blank, and every provider failure is
 * caught and downgraded to a result rather than propagated.
 */
@Component
public class EmailChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);

    private final ResendClient resendClient;
    private final boolean enabled;

    public EmailChannel(ResendClient resendClient, @Value("${resend.api-key:}") String apiKey) {
        this.resendClient = resendClient;
        this.enabled = apiKey != null && !apiKey.isBlank();
        if (!enabled) {
            log.info("Resend email sending is disabled (RESEND_API_KEY is unset)");
        }
    }

    @Override
    public Channel channel() {
        return Channel.EMAIL;
    }

    @Override
    public Mono<DeliveryResult> send(RenderedMessage message) {
        if (!enabled) {
            log.info("[email disabled] would send {} to <redacted>", message.subject());
            return Mono.just(DeliveryResult.skippedDisabled());
        }
        if (message.recipient() == null || message.recipient().isBlank()) {
            return Mono.just(DeliveryResult.failed("no recipient address"));
        }
        return resendClient.send(message.recipient(), message.subject(), message.html(), message.text())
                .doOnNext(id -> log.info("email sent, resend id={}", id))
                .map(DeliveryResult::sent)
                .onErrorResume(ex -> {
                    log.warn("failed to send email: {}", ex.getMessage());
                    return Mono.just(DeliveryResult.failed(ex.getMessage()));
                });
    }
}
