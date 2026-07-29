package org.granitesecurity.authserver.user;

import org.granitesecurity.authserver.client.NotificationEventPublisher;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class FederatedUserProvisioningService {

    private static final String GOOGLE = "GOOGLE";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationEventPublisher notificationEventPublisher;

    public FederatedUserProvisioningService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                            NotificationEventPublisher notificationEventPublisher) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.notificationEventPublisher = notificationEventPublisher;
    }

    @Transactional
    public UserEntity provision(String subject, String email, boolean emailVerified, String givenName, String familyName) {
        if (!emailVerified) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("email_not_verified"), "Google account email is not verified");
        }

        String normalizedEmail = email.toLowerCase(Locale.ROOT);

        // A returning Google user. No event: they were announced when their
        // account was first created, and this branch runs on every single login.
        Optional<UserEntity> byProviderId = userRepository.findByProviderAndProviderId(GOOGLE, subject);
        if (byProviderId.isPresent()) {
            UserEntity user = byProviderId.get();
            user.setFirstName(givenName);
            user.setLastName(familyName);
            return userRepository.save(user);
        }

        // Linking Google to an account that registered with the form. Also no
        // event: they already have a profile row and already got their welcome
        // mail at registration. Nothing has been registered here — a second
        // login method has been attached to an existing identity.
        Optional<UserEntity> byEmail = userRepository.findByEmailIgnoreCase(normalizedEmail);
        if (byEmail.isPresent()) {
            UserEntity user = byEmail.get();
            // Keep provider = LOCAL so the existing password still works; only
            // link the Google subject so both login methods resolve to this row.
            user.setProviderId(subject);
            return userRepository.save(user);
        }

        UserEntity user = new UserEntity();
        user.setUsername(generateUsername(normalizedEmail));
        user.setEmail(normalizedEmail);
        // Google-provisioned accounts have no password of their own; a random
        // unguessable value keeps the NOT NULL column satisfied without a
        // usable form-login credential (see JpaUserDetailsService's guard).
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setFirstName(givenName);
        user.setLastName(familyName);
        user.setEnabled(true);
        user.setProvider(GOOGLE);
        user.setProviderId(subject);
        UserRegistrationService.grantDefaultAuthorities(user);
        UserEntity saved = userRepository.save(user);

        // A Google sign-in that creates an account IS a registration, and until
        // now it announced itself to nobody: only the form path published this
        // event, so profile never learned a federated user's email or name (its
        // row stayed a username-only stub) and notification never sent them a
        // welcome mail. Same payload and same afterCommit discipline as
        // UserRegistrationService — a rolled-back provisioning must not announce
        // an account that does not exist.
        String username = saved.getUsername();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notificationEventPublisher.publishUserRegistered(
                        username, normalizedEmail, givenName, familyName);
            }
        });

        return saved;
    }

    private String generateUsername(String email) {
        String localPart = email.substring(0, email.indexOf('@'));
        String base = localPart.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
        if (base.isBlank()) {
            base = "user";
        }

        String candidate = base;
        int suffix = 1;
        while (userRepository.existsByUsernameIgnoreCase(candidate)) {
            candidate = base + suffix++;
        }
        return candidate;
    }
}
