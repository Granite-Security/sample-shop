package org.granitesecurity.authserver.user;

import org.granitesecurity.authserver.client.NotificationEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FederatedUserProvisioningServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private NotificationEventPublisher notificationEventPublisher;

    private FederatedUserProvisioningService service;

    // provision() now registers an afterCommit synchronization, which throws
    // without an active one. Same setup as PasswordChangeServiceTest.
    @BeforeEach
    void initSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void rejectsUnverifiedEmail() {
        service = new FederatedUserProvisioningService(userRepository, passwordEncoder, notificationEventPublisher);

        assertThatThrownBy(() ->
                service.provision("google-sub", "jdoe@example.com", false, "John", "Doe"))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void createsNewUserWhenNoExistingRecordMatches() {
        service = new FederatedUserProvisioningService(userRepository, passwordEncoder, notificationEventPublisher);
        when(userRepository.findByProviderAndProviderId("GOOGLE", "google-sub")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("jdoe@example.com")).thenReturn(Optional.empty());
        when(userRepository.existsByUsernameIgnoreCase("jdoe")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("{bcrypt}encoded");

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        when(userRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        UserEntity user = service.provision("google-sub", "jdoe@example.com", true, "John", "Doe");

        assertThat(user.getUsername()).isEqualTo("jdoe");
        assertThat(user.getProvider()).isEqualTo("GOOGLE");
        assertThat(user.getProviderId()).isEqualTo("google-sub");
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getAuthorities())
                .extracting(AuthorityEntity::getAuthority)
                .containsExactlyInAnyOrder("ROLE_USER", "USER");
    }

    @Test
    void repeatLoginByProviderIdIsIdempotent() {
        service = new FederatedUserProvisioningService(userRepository, passwordEncoder, notificationEventPublisher);
        UserEntity existing = new UserEntity();
        existing.setUsername("jdoe");
        existing.setEmail("jdoe@example.com");
        existing.setProvider("GOOGLE");
        existing.setProviderId("google-sub");
        when(userRepository.findByProviderAndProviderId("GOOGLE", "google-sub")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserEntity user = service.provision("google-sub", "jdoe@example.com", true, "John", "Doe");

        assertThat(user).isSameAs(existing);
        assertThat(user.getFirstName()).isEqualTo("John");
    }

    @Test
    void linksExistingLocalUserByEmailWithoutChangingProvider() {
        service = new FederatedUserProvisioningService(userRepository, passwordEncoder, notificationEventPublisher);
        UserEntity localUser = new UserEntity();
        localUser.setUsername("jdoe");
        localUser.setEmail("jdoe@example.com");
        localUser.setProvider("LOCAL");
        localUser.setPassword("{bcrypt}existinghash");
        when(userRepository.findByProviderAndProviderId("GOOGLE", "google-sub")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("jdoe@example.com")).thenReturn(Optional.of(localUser));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserEntity user = service.provision("google-sub", "jdoe@example.com", true, "John", "Doe");

        assertThat(user.getProvider()).isEqualTo("LOCAL");
        assertThat(user.getProviderId()).isEqualTo("google-sub");
        assertThat(user.getPassword()).isEqualTo("{bcrypt}existinghash");
    }

    @Test
    void usernameCollisionGetsNumericSuffix() {
        service = new FederatedUserProvisioningService(userRepository, passwordEncoder, notificationEventPublisher);
        when(userRepository.findByProviderAndProviderId("GOOGLE", "google-sub")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("jdoe@example.com")).thenReturn(Optional.empty());
        when(userRepository.existsByUsernameIgnoreCase("jdoe")).thenReturn(true);
        when(userRepository.existsByUsernameIgnoreCase("jdoe1")).thenReturn(true);
        when(userRepository.existsByUsernameIgnoreCase("jdoe2")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("{bcrypt}encoded");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserEntity user = service.provision("google-sub", "jdoe@example.com", true, "John", "Doe");

        assertThat(user.getUsername()).isEqualTo("jdoe2");
    }
}
