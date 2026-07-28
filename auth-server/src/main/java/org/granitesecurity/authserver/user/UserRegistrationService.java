package org.granitesecurity.authserver.user;

import org.granitesecurity.authserver.client.NotificationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Locale;
import java.util.Set;

@Service
public class UserRegistrationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationEventPublisher notificationEventPublisher;

    public UserRegistrationService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                   NotificationEventPublisher notificationEventPublisher) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.notificationEventPublisher = notificationEventPublisher;
    }

    @Transactional
    public RegistrationResponse register(RegistrationRequest request) {
        String username = request.username().toLowerCase(Locale.ROOT);
        String email = request.email().toLowerCase(Locale.ROOT);

        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new DuplicateUserException("username", "Username is already taken");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new DuplicateUserException("email", "Email is already registered");
        }

        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setEnabled(true);
        user.setProvider("LOCAL");
        user.setProviderId(null);
        grantDefaultAuthorities(user);

        try {
            UserEntity saved = userRepository.save(user);

            // New capability: registration never sent mail before. Same afterCommit
            // discipline as the password flows — a rolled-back registration must not
            // produce a welcome email for an account that does not exist.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notificationEventPublisher.publishUserRegistered(
                            username, email, request.firstName(), request.lastName());
                }
            });

            return new RegistrationResponse(saved.getUsername(), saved.getEmail());
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateUserException("username", "Username or email is already taken");
        }
    }

    static void grantDefaultAuthorities(UserEntity user) {
        for (String authority : Set.of("ROLE_USER", "USER")) {
            AuthorityEntity authorityEntity = new AuthorityEntity();
            authorityEntity.setUser(user);
            authorityEntity.setAuthority(authority);
            user.getAuthorities().add(authorityEntity);
        }
    }
}
