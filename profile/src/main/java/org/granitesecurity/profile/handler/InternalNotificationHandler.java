package org.granitesecurity.profile.handler;

import org.granitesecurity.profile.dto.PasswordChangedNotifyRequest;
import org.granitesecurity.profile.dto.PasswordResetRequestedNotifyRequest;
import org.granitesecurity.profile.notification.EmailService;
import org.granitesecurity.profile.repository.UserProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

@Service
public class InternalNotificationHandler {

    private final UserProfileRepository userProfileRepository;
    private final EmailService emailService;

    public InternalNotificationHandler(UserProfileRepository userProfileRepository, EmailService emailService) {
        this.userProfileRepository = userProfileRepository;
        this.emailService = emailService;
    }

    public Mono<ServerResponse> passwordChanged(ServerRequest request) {
        String username = request.pathVariable("username");
        return request.bodyToMono(PasswordChangedNotifyRequest.class)
                .defaultIfEmpty(new PasswordChangedNotifyRequest(null))
                .flatMap(body -> resolveEmail(username, body.email())
                        .flatMap(email -> emailService.sendPasswordChanged(email, username, Instant.now()))
                        .defaultIfEmpty(false))
                .flatMap(sent -> ServerResponse.ok().bodyValue(Map.of("sent", sent)));
    }

    public Mono<ServerResponse> passwordResetRequested(ServerRequest request) {
        String username = request.pathVariable("username");
        return request.bodyToMono(PasswordResetRequestedNotifyRequest.class)
                .flatMap(body -> resolveEmail(username, body.email())
                        .flatMap(email -> emailService.sendPasswordResetRequested(email, username, body.resetLink()))
                        .defaultIfEmpty(false))
                .flatMap(sent -> ServerResponse.ok().bodyValue(Map.of("sent", sent)));
    }

    private Mono<String> resolveEmail(String username, String bodyEmail) {
        if (bodyEmail != null && !bodyEmail.isBlank()) {
            return Mono.just(bodyEmail);
        }
        return userProfileRepository.findByUsername(username)
                .flatMap(profile -> profile.getEmail() != null && !profile.getEmail().isBlank()
                        ? Mono.just(profile.getEmail())
                        : Mono.empty());
    }
}
