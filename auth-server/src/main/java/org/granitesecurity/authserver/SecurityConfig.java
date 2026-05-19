package org.granitesecurity.authserver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

@Configuration
public class SecurityConfig {

    @Bean
    public InMemoryUserDetailsManager userDetailsService() {
        UserDetails user = User.withUsername("user")
                .password("{noop}password")
                .roles("USER")
                .build();
        UserDetails iaka = User.withUsername("iaka")
                .password("{noop}a")
                .roles("USER", "ADMIN")
                .build();
        return new InMemoryUserDetailsManager(
                user, iaka
        );
    }
}
