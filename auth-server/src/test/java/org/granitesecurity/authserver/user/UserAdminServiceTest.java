package org.granitesecurity.authserver.user;

import org.granitesecurity.authserver.AbstractTestcontainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class UserAdminServiceTest extends AbstractTestcontainers {

    @Autowired
    private UserAdminService userAdminService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private UserEntity newUser(String username) {
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPassword("{bcrypt}$2a$10$abcdefghijklmnopqrstuv");
        user.setEnabled(true);
        AuthorityEntity authority = new AuthorityEntity();
        authority.setAuthority("ROLE_USER");
        authority.setUser(user);
        user.getAuthorities().add(authority);
        return userRepository.save(user);
    }

    @Test
    void blockDisablesAndRecordsWhoAndWhen() {
        newUser("blockme");

        AdminUserResponse blocked = userAdminService.block("blockme", "admin");

        assertThat(blocked.enabled()).isFalse();
        assertThat(blocked.blockedBy()).isEqualTo("admin");
        assertThat(blocked.blockedAt()).isNotNull();
    }

    @Test
    void unblockClearsTheBlockRecord() {
        newUser("unblockme");
        userAdminService.block("unblockme", "admin");

        AdminUserResponse active = userAdminService.unblock("unblockme");

        assertThat(active.enabled()).isTrue();
        assertThat(active.blockedAt()).isNull();
        assertThat(active.blockedBy()).isNull();
    }

    @Test
    void unblockingAnActiveUserIsAConflictNotASilentSuccess() {
        newUser("stillactive");

        assertThatThrownBy(() -> userAdminService.unblock("stillactive"))
                .isInstanceOf(UserNotBlockedException.class);
    }

    @Test
    void deleteRemovesAuthoritiesAndResetTokens() {
        UserEntity user = newUser("deleteme");
        Long userId = user.getId();
        PasswordResetTokenEntity token = new PasswordResetTokenEntity();
        token.setUserId(user.getId());
        token.setTokenHash("hash-for-deleteme");
        token.setExpiresAt(OffsetDateTime.now().plusHours(1));
        passwordResetTokenRepository.saveAndFlush(token);

        userAdminService.delete("deleteme");

        assertThat(userRepository.findByUsername("deleteme")).isEmpty();
        // FK cascades, not application code — nothing to keep in step.
        assertThat(passwordResetTokenRepository.findByTokenHash("hash-for-deleteme")).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM authorities WHERE user_id = ?", Integer.class, userId))
                .isZero();
    }

    @Test
    void unknownUsernameIsNotFound() {
        assertThatThrownBy(() -> userAdminService.delete("nobody-here"))
                .isInstanceOf(UserNotFoundException.class);
    }
}
