package org.granitesecurity.authserver.user;

import org.granitesecurity.authserver.client.ProfileNotificationClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordChangeServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProfileNotificationClient profileNotificationClient;

    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    private PasswordChangeService service;

    @BeforeEach
    void setUp() {
        service = new PasswordChangeService(userRepository, passwordEncoder, profileNotificationClient);
        // changePassword registers an afterCommit synchronization for the
        // notify hop; without an active synchronization scope that call
        // throws, so simulate one the way a real @Transactional method would.
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void rejectsIncorrectCurrentPassword() {
        UserEntity user = localUser("alice", "correct-pass");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.changePassword("alice",
                new ChangePasswordRequest("wrong-pass", "new-password-123")))
                .isInstanceOf(IncorrectPasswordException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void rejectsNonLocalAccount() {
        UserEntity user = localUser("google-user", "whatever");
        user.setProvider("GOOGLE");
        when(userRepository.findByUsername("google-user")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.changePassword("google-user",
                new ChangePasswordRequest("whatever", "new-password-123")))
                .isInstanceOf(NonLocalAccountException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void rejectsSameAsOldPassword() {
        UserEntity user = localUser("alice", "correct-pass");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.changePassword("alice",
                new ChangePasswordRequest("correct-pass", "correct-pass")))
                .isInstanceOf(SamePasswordException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void happyPathEncodesPasswordAndNotifiesAfterCommit() {
        UserEntity user = localUser("alice", "correct-pass");
        user.setEmail("alice@example.com");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        service.changePassword("alice", new ChangePasswordRequest("correct-pass", "new-password-123"));

        verify(userRepository).save(user);
        assertThat(passwordEncoder.matches("new-password-123", user.getPassword())).isTrue();

        // Notification is deferred to an afterCommit synchronization, not
        // fired inline — simulate the commit to verify it's wired correctly.
        verify(profileNotificationClient, never()).notifyPasswordChanged(any(), any());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        verify(profileNotificationClient).notifyPasswordChanged("alice", "alice@example.com");
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
