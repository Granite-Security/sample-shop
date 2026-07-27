package org.granitesecurity.authserver.user;

import org.granitesecurity.authserver.AbstractTestcontainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class UserRepositoryLiquibaseTest extends AbstractTestcontainers {

    @Autowired
    private UserRepository userRepository;

    private UserEntity newUser(String username, String email) {
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword("{bcrypt}$2a$10$abcdefghijklmnopqrstuv");
        user.setEnabled(true);
        return user;
    }

    @Test
    void changelog003AppliesAndProgrammaticInsertPopulatesTimestamps() {
        UserEntity saved = userRepository.save(newUser("liquibasetest", "liquibasetest@example.com"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getProvider()).isEqualTo("LOCAL");
        assertThat(saved.getProviderId()).isNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void multipleLocalUsersWithNullProviderIdCoexist() {
        userRepository.save(newUser("localuser1", "localuser1@example.com"));
        userRepository.save(newUser("localuser2", "localuser2@example.com"));

        assertThat(userRepository.existsByUsernameIgnoreCase("localuser1")).isTrue();
        assertThat(userRepository.existsByUsernameIgnoreCase("localuser2")).isTrue();
    }

    @Test
    void duplicateProviderAndProviderIdViolatesUniqueIndex() {
        UserEntity first = newUser("googleuser1", "googleuser1@example.com");
        first.setProvider("GOOGLE");
        first.setProviderId("dup-sub");
        userRepository.saveAndFlush(first);

        UserEntity second = newUser("googleuser2", "googleuser2@example.com");
        second.setProvider("GOOGLE");
        second.setProviderId("dup-sub");

        assertThatThrownBy(() -> userRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
