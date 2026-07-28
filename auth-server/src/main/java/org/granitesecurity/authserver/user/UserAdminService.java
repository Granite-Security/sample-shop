package org.granitesecurity.authserver.user;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Executes identity changes on behalf of profile. There is no role logic and no
 * order logic here — profile decides who may do this and whether a delete is
 * allowed; this class does what it is told and reports what happened
 * (docs/users/blocking-users.md §5.2).
 */
@Service
public class UserAdminService {

    private final UserRepository userRepository;

    public UserAdminService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<AdminUserResponse> listUsers() {
        return userRepository.findAll(Sort.by("username")).stream()
                .map(AdminUserResponse::from)
                .toList();
    }

    /**
     * Idempotent: blocking an already-blocked user refreshes who blocked them
     * and when, rather than failing. Only <em>un</em>blocking is state-sensitive
     * (§7), because "unblock" on an active user usually means the admin acted on
     * a stale list.
     */
    @Transactional
    public AdminUserResponse block(String username, String actor) {
        UserEntity user = require(username);
        user.setEnabled(false);
        user.setBlockedAt(OffsetDateTime.now());
        user.setBlockedBy(actor);
        return AdminUserResponse.from(userRepository.save(user));
    }

    @Transactional
    public AdminUserResponse unblock(String username) {
        UserEntity user = require(username);
        if (user.isEnabled()) {
            throw new UserNotBlockedException("User is not blocked: " + username);
        }
        user.setEnabled(true);
        user.setBlockedAt(null);
        user.setBlockedBy(null);
        return AdminUserResponse.from(userRepository.save(user));
    }

    /**
     * A plain row delete. `authorities` goes with it through the JPA cascade,
     * and `password_reset_token` through its ON DELETE CASCADE FK — no separate
     * cleanup to keep in step.
     */
    @Transactional
    public void delete(String username) {
        userRepository.delete(require(username));
    }

    private UserEntity require(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("No such user: " + username));
    }
}
