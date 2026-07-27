package org.granitesecurity.profile.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final ResendClient resendClient;
    private final boolean enabled;

    public EmailService(ResendClient resendClient, @Value("${resend.api-key:}") String apiKey) {
        this.resendClient = resendClient;
        this.enabled = apiKey != null && !apiKey.isBlank();
        if (!enabled) {
            log.info("Resend email sending is disabled (RESEND_API_KEY is unset)");
        }
    }

    public Mono<Boolean> sendPasswordChanged(String to, String displayName, Instant when) {
        if (!enabled || to == null || to.isBlank()) {
            log.info("[email disabled] would send password-changed to <redacted>");
            return Mono.just(false);
        }
        String subject = EmailTemplates.passwordChangedSubject();
        String html = EmailTemplates.passwordChangedHtml(displayName, when);
        String text = EmailTemplates.passwordChangedText(displayName, when);
        return resendClient.send(to, subject, html, text)
                .doOnNext(id -> log.info("password-changed email sent, resend id={}", id))
                .map(id -> true)
                .onErrorResume(ex -> {
                    log.warn("failed to send password-changed email: {}", ex.getMessage());
                    return Mono.just(false);
                });
    }

    public Mono<Boolean> sendWelcome(String to, String displayName) {
        if (!enabled || to == null || to.isBlank()) {
            log.info("[email disabled] would send welcome to <redacted>");
            return Mono.just(false);
        }
        String subject = EmailTemplates.welcomeSubject();
        String html = EmailTemplates.welcomeHtml(displayName);
        String text = EmailTemplates.welcomeText(displayName);
        return resendClient.send(to, subject, html, text)
                .doOnNext(id -> log.info("welcome email sent, resend id={}", id))
                .map(id -> true)
                .onErrorResume(ex -> {
                    log.warn("failed to send welcome email: {}", ex.getMessage());
                    return Mono.just(false);
                });
    }

    public Mono<Boolean> sendPasswordResetRequested(String to, String displayName, String resetLink) {
        if (!enabled || to == null || to.isBlank()) {
            log.info("[email disabled] would send password-reset-requested to <redacted>");
            return Mono.just(false);
        }
        String subject = EmailTemplates.passwordResetRequestedSubject();
        String html = EmailTemplates.passwordResetRequestedHtml(displayName, resetLink);
        String text = EmailTemplates.passwordResetRequestedText(displayName, resetLink);
        return resendClient.send(to, subject, html, text)
                .doOnNext(id -> log.info("password-reset-requested email sent, resend id={}", id))
                .map(id -> true)
                .onErrorResume(ex -> {
                    log.warn("failed to send password-reset-requested email: {}", ex.getMessage());
                    return Mono.just(false);
                });
    }
}
