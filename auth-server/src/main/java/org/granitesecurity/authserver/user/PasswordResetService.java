package org.granitesecurity.authserver.user;

import org.granitesecurity.authserver.client.NotificationEventPublisher;
import org.granitesecurity.authserver.client.ProfileNotificationClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class PasswordResetService {

    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final ProfileNotificationClient profileNotificationClient;
    private final NotificationEventPublisher notificationEventPublisher;
    private final String frontendOrigin;
    private final SecureRandom secureRandom = new SecureRandom();

    public PasswordResetService(UserRepository userRepository,
                                 PasswordResetTokenRepository passwordResetTokenRepository,
                                 PasswordEncoder passwordEncoder,
                                 ProfileNotificationClient profileNotificationClient,
                                 NotificationEventPublisher notificationEventPublisher,
                                 @Value("${app.oauth2.spa-client-shop.post-logout-redirect-uri:"
                                         + "${app.oauth2.spa-client.post-logout-redirect-uri:http://localhost:5173}}")
                                 String frontendOrigin) {
        this.userRepository = userRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.profileNotificationClient = profileNotificationClient;
        this.notificationEventPublisher = notificationEventPublisher;
        this.frontendOrigin = frontendOrigin;
    }

    @Transactional
    public void requestReset(String email) {
        userRepository.findByEmailIgnoreCase(email).ifPresent(user -> {
            // Enumeration-safe: the caller (an always-200 controller) can't
            // distinguish "no such account", "Google account", or "email
            // queued" from each other — this silently no-ops for both.
            if (!"LOCAL".equals(user.getProvider())) {
                return;
            }

            String rawToken = generateToken();
            PasswordResetTokenEntity token = new PasswordResetTokenEntity();
            token.setUserId(user.getId());
            token.setTokenHash(hash(rawToken));
            token.setExpiresAt(OffsetDateTime.now().plus(TOKEN_TTL));
            passwordResetTokenRepository.save(token);

            String username = user.getUsername();
            String resetLink = frontendOrigin + "/reset-password/confirm?token=" + rawToken;
            java.time.Instant expiresAt = token.getExpiresAt().toInstant();

            // Only email the link once the token row has actually committed —
            // same reasoning as PasswordChangeService's afterCommit notify.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // Dual-write during the migration. Note the event carries the raw
                    // token rather than the rendered link — the notification service
                    // builds the URL from its own frontend origin (D3).
                    profileNotificationClient.notifyPasswordResetRequested(username, email, resetLink);
                    notificationEventPublisher.publishPasswordResetRequested(username, email, rawToken, expiresAt);
                }
            });
        });
    }

    @Transactional
    public void confirmReset(String rawToken, String newPassword) {
        PasswordResetTokenEntity token = passwordResetTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(PasswordResetService::invalidToken);

        if (token.getUsedAt() != null || token.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw invalidToken();
        }

        UserEntity user = userRepository.findById(token.getUserId())
                .orElseThrow(PasswordResetService::invalidToken);

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        token.setUsedAt(OffsetDateTime.now());
        passwordResetTokenRepository.save(token);

        String username = user.getUsername();
        String email = user.getEmail();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                profileNotificationClient.notifyPasswordChanged(username, email);
                notificationEventPublisher.publishPasswordChanged(username, email);
            }
        });
    }

    private static InvalidResetTokenException invalidToken() {
        return new InvalidResetTokenException("This reset link is invalid or has expired.");
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
