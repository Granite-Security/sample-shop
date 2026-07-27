package org.granitesecurity.profile.notification;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

// This app doesn't autoconfigure a WebClient.Builder bean (unlike profile's
// own InternalClientConfig, which builds its OAuth2-filtered WebClient
// directly rather than depending on an injected builder) — ResendClient needs
// a plain one to build against, so provide it explicitly.
@Configuration
class NotificationConfig {

    @Bean
    WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
