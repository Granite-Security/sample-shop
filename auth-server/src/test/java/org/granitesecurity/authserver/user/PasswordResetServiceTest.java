package org.granitesecurity.authserver.user;

import org.granitesecurity.authserver.client.ProfileNotificationClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    private static final String FRONTEND_ORIGIN = "https://granite-security.org";

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Mock
    private ProfileNotificationClient profileNotificationClient;

    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(userRepository, passwordResetTokenRepository, passwordEncoder,
                profileNotificationClient, FRONTEND_ORIGIN);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void requestResetNoOpsForUnknownEmail() {
        when(userRepository.findByEmailIgnoreCase("ghost@example.com")).thenReturn(Optional.empty());

        service.requestReset("ghost@example.com");

        verify(passwordResetTokenRepository, never()).save(any());
        runAfterCommitCallbacks();
        verify(profileNotificationClient, never()).notifyPasswordResetRequested(any(), any(), any());
    }

    @Test
    void requestResetNoOpsForNonLocalAccount() {
        UserEntity user = localUser("google-user", "whatever");
        user.setProvider("GOOGLE");
        user.setEmail("google-user@example.com");
        when(userRepository.findByEmailIgnoreCase("google-user@example.com")).thenReturn(Optional.of(user));

        service.requestReset("google-user@example.com");

        verify(passwordResetTokenRepository, never()).save(any());
        runAfterCommitCallbacks();
        verify(profileNotificationClient, never()).notifyPasswordResetRequested(any(), any(), any());
    }

    @Test
    void requestResetSavesTokenAndNotifiesAfterCommit() {
        UserEntity user = localUser("alice", "correct-pass");
        user.setId(1L);
        user.setEmail("alice@example.com");
        when(userRepository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(user));

        service.requestReset("alice@example.com");

        ArgumentCaptor<PasswordResetTokenEntity> captor = ArgumentCaptor.forClass(PasswordResetTokenEntity.class);
        verify(passwordResetTokenRepository).save(captor.capture());
        PasswordResetTokenEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getTokenHash()).isNotBlank();
        assertThat(saved.getUsedAt()).isNull();
        assertThat(saved.getExpiresAt()).isAfter(OffsetDateTime.now());

        verify(profileNotificationClient, never()).notifyPasswordResetRequested(any(), any(), any());
        runAfterCommitCallbacks();
        verify(profileNotificationClient).notifyPasswordResetRequested(eq("alice"), eq("alice@example.com"),
                contains(FRONTEND_ORIGIN + "/reset-password/confirm?token="));
    }

    @Test
    void confirmResetRejectsUnknownToken() {
        when(passwordResetTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmReset("bogus-token", "new-password-123"))
                .isInstanceOf(InvalidResetTokenException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void confirmResetRejectsExpiredToken() {
        String rawToken = "some-raw-token";
        PasswordResetTokenEntity token = tokenFor(1L, rawToken);
        token.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        when(passwordResetTokenRepository.findByTokenHash(sha256Hex(rawToken))).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.confirmReset(rawToken, "new-password-123"))
                .isInstanceOf(InvalidResetTokenException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void confirmResetRejectsAlreadyUsedToken() {
        String rawToken = "some-raw-token";
        PasswordResetTokenEntity token = tokenFor(1L, rawToken);
        token.setUsedAt(OffsetDateTime.now().minusMinutes(5));
        when(passwordResetTokenRepository.findByTokenHash(sha256Hex(rawToken))).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.confirmReset(rawToken, "new-password-123"))
                .isInstanceOf(InvalidResetTokenException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void confirmResetHappyPathUpdatesPasswordAndNotifiesAfterCommit() {
        String rawToken = "some-raw-token";
        PasswordResetTokenEntity token = tokenFor(1L, rawToken);
        when(passwordResetTokenRepository.findByTokenHash(sha256Hex(rawToken))).thenReturn(Optional.of(token));

        UserEntity user = localUser("alice", "old-password");
        user.setId(1L);
        user.setEmail("alice@example.com");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        service.confirmReset(rawToken, "new-password-123");

        verify(userRepository).save(user);
        assertThat(passwordEncoder.matches("new-password-123", user.getPassword())).isTrue();
        assertThat(token.getUsedAt()).isNotNull();
        verify(passwordResetTokenRepository).save(token);

        verify(profileNotificationClient, never()).notifyPasswordChanged(any(), any());
        runAfterCommitCallbacks();
        verify(profileNotificationClient).notifyPasswordChanged("alice", "alice@example.com");
    }

    private static void runAfterCommitCallbacks() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
    }

    private PasswordResetTokenEntity tokenFor(Long userId, String rawToken) {
        PasswordResetTokenEntity token = new PasswordResetTokenEntity();
        token.setUserId(userId);
        token.setTokenHash(sha256Hex(rawToken));
        token.setExpiresAt(OffsetDateTime.now().plusMinutes(30));
        return token;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private UserEntity localUser(String username, String rawPassword) {
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setProvider("LOCAL");
        user.setEnabled(true);
        return user;
    }
}
