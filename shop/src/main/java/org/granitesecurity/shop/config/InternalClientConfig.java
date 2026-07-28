package org.granitesecurity.shop.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

/** Mirrors profile's InternalClientConfig — same client-credentials shape. */
@Configuration
public class InternalClientConfig {

    @Bean
    public ReactiveOAuth2AuthorizedClientManager authorizedClientManager(
            ReactiveClientRegistrationRepository clientRegistrationRepository,
            ReactiveOAuth2AuthorizedClientService authorizedClientService) {
        ReactiveOAuth2AuthorizedClientProvider authorizedClientProvider =
                ReactiveOAuth2AuthorizedClientProviderBuilder.builder()
                        .clientCredentials()
                        .build();

        var manager = new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(
                clientRegistrationRepository, authorizedClientService);
        manager.setAuthorizedClientProvider(authorizedClientProvider);
        return manager;
    }

    @Bean
    public WebClient paymentWebClient(ReactiveOAuth2AuthorizedClientManager authorizedClientManager,
                                      @Value("${payment-service.uri}") String paymentUri) {
        var oauth2 = new ServerOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
        oauth2.setDefaultClientRegistrationId("payment-client");
        return WebClient.builder()
                .baseUrl(paymentUri)
                .filter(oauth2)
                .build();
    }
}
