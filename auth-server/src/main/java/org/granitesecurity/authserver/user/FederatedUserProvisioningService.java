package org.granitesecurity.authserver.user;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class FederatedUserProvisioningService {

    private static final String GOOGLE = "GOOGLE";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public FederatedUserProvisioningService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserEntity provision(String subject, String email, boolean emailVerified, String givenName, String familyName) {
        if (!emailVerified) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("email_not_verified"), "Google account email is not verified");
        }

        String normalizedEmail = email.toLowerCase(Locale.ROOT);

        Optional<UserEntity> byProviderId = userRepository.findByProviderAndProviderId(GOOGLE, subject);
        if (byProviderId.isPresent()) {
            UserEntity user = byProviderId.get();
            user.setFirstName(givenName);
            user.setLastName(familyName);
            return userRepository.save(user);
        }

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
        return userRepository.save(user);
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
