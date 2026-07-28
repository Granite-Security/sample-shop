package org.granitesecurity.authserver.user;

import org.granitesecurity.authserver.client.NotificationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class PasswordChangeService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationEventPublisher notificationEventPublisher;

    public PasswordChangeService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                  NotificationEventPublisher notificationEventPublisher) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.notificationEventPublisher = notificationEventPublisher;
    }

    @Transactional
    public void changePassword(String username, ChangePasswordRequest request) {
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("authenticated user not found: " + username));

        // Google users hold a generated, unguessable password with nothing
        // for them to "change" — reject before touching the encoder at all.
        if (!"LOCAL".equals(user.getProvider())) {
            throw new NonLocalAccountException(
                    "This account signs in with Google; there is no password to change.");
        }

        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new IncorrectPasswordException("Current password is incorrect");
        }

        if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
            throw new SamePasswordException("New password must differ from the current password");
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        // Only notify once the change has actually committed — a rollback
        // (e.g. a later validation failure in the same transaction) must not
        // still send a "your password was changed" email.
        String email = user.getEmail();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notificationEventPublisher.publishPasswordChanged(username, email);
            }
        });
    }
}
