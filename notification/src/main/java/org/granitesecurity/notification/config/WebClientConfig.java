package org.granitesecurity.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

// This app doesn't autoconfigure a WebClient.Builder bean, and ResendClient needs
// a plain one to build against — provide it explicitly. Carried over from
// profile's NotificationConfig.
@Configuration
class WebClientConfig {

    @Bean
    WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
