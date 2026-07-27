package org.granitesecurity.authserver.user;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JpaUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @Test
    void nullPasswordRowIsNotLoadable() {
        UserEntity googleOnlyUser = new UserEntity();
        googleOnlyUser.setUsername("googleuser");
        googleOnlyUser.setPassword(null);
        googleOnlyUser.setEnabled(true);
        when(userRepository.findByUsername("googleuser")).thenReturn(Optional.of(googleOnlyUser));

        JpaUserDetailsService service = new JpaUserDetailsService(userRepository);

        assertThatThrownBy(() -> service.loadUserByUsername("googleuser"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
