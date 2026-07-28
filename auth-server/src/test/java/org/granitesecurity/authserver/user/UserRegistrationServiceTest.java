package org.granitesecurity.authserver.user;

import org.granitesecurity.authserver.client.NotificationEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserRegistrationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private NotificationEventPublisher notificationEventPublisher;

    private UserRegistrationService service;

    // register() now publishes UserRegistered from an afterCommit synchronization;
    // registerSynchronization throws without an active scope, so simulate the one a
    // real @Transactional method would provide (same as PasswordChangeServiceTest).
    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private RegistrationRequest request(String username, String email) {
        return new RegistrationRequest(username, email, "password123", "First", "Last");
    }

    @Test
    void normalizesUsernameAndEmailToLowerCase() {
        service = new UserRegistrationService(userRepository, passwordEncoder, notificationEventPublisher);
        when(userRepository.existsByUsernameIgnoreCase("jdoe")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("jdoe@example.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("{bcrypt}encoded");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        RegistrationResponse response = service.register(request("JDoe", "JDoe@Example.com"));

        assertThat(response.username()).isEqualTo("jdoe");
        assertThat(response.email()).isEqualTo("jdoe@example.com");
    }

    @Test
    void rejectsDuplicateUsername() {
        service = new UserRegistrationService(userRepository, passwordEncoder, notificationEventPublisher);
        when(userRepository.existsByUsernameIgnoreCase("jdoe")).thenReturn(true);

        assertThatThrownBy(() -> service.register(request("jdoe", "jdoe@example.com")))
                .isInstanceOf(DuplicateUserException.class)
                .satisfies(ex -> assertThat(((DuplicateUserException) ex).getField()).isEqualTo("username"));
    }

    @Test
    void rejectsDuplicateEmail() {
        service = new UserRegistrationService(userRepository, passwordEncoder, notificationEventPublisher);
        when(userRepository.existsByUsernameIgnoreCase("jdoe")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("jdoe@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register(request("jdoe", "jdoe@example.com")))
                .isInstanceOf(DuplicateUserException.class)
                .satisfies(ex -> assertThat(((DuplicateUserException) ex).getField()).isEqualTo("email"));
    }

    @Test
    void grantsRoleUserAndUserAuthorities() {
        service = new UserRegistrationService(userRepository, passwordEncoder, notificationEventPublisher);
        when(userRepository.existsByUsernameIgnoreCase("jdoe")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("jdoe@example.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("{bcrypt}encoded");

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        when(userRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.register(request("jdoe", "jdoe@example.com"));

        UserEntity saved = captor.getValue();
        assertThat(saved.getAuthorities())
                .extracting(AuthorityEntity::getAuthority)
                .containsExactlyInAnyOrder("ROLE_USER", "USER");
    }

    @Test
    void encodesPasswordAndDoesNotStoreRawValue() {
        service = new UserRegistrationService(userRepository, passwordEncoder, notificationEventPublisher);
        when(userRepository.existsByUsernameIgnoreCase("jdoe")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("jdoe@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("{bcrypt}$2a$10$encodedvalue");

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        when(userRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.register(request("jdoe", "jdoe@example.com"));

        assertThat(captor.getValue().getPassword()).isEqualTo("{bcrypt}$2a$10$encodedvalue");
        assertThat(captor.getValue().getPassword()).doesNotContain("password123");
    }

    @Test
    void mapsDataIntegrityViolationToDuplicateUserException() {
        service = new UserRegistrationService(userRepository, passwordEncoder, notificationEventPublisher);
        when(userRepository.existsByUsernameIgnoreCase("jdoe")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("jdoe@example.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("{bcrypt}encoded");
        when(userRepository.save(any(UserEntity.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> service.register(request("jdoe", "jdoe@example.com")))
                .isInstanceOf(DuplicateUserException.class);
    }
}
