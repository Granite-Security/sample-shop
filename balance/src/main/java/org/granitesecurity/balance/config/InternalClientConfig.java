package org.granitesecurity.balance.config;

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

/** Copied from profile's InternalClientConfig — client-credentials, internal scope. */
@Configuration
public class InternalClientConfig {

    @Bean
    public ReactiveOAuth2AuthorizedClientManager authorizedClientManager(
            ReactiveClientRegistrationRepository clientRegistrationRepository,
            ReactiveOAuth2AuthorizedClientService authorizedClientService) {
        ReactiveOAuth2AuthorizedClientProvider provider =
                ReactiveOAuth2AuthorizedClientProviderBuilder.builder()
                        .clientCredentials()
                        .build();

        var manager = new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(
                clientRegistrationRepository, authorizedClientService);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    @Bean
    public WebClient profileWebClient(ReactiveOAuth2AuthorizedClientManager authorizedClientManager,
                                      @Value("${microservices.profile.uri}") String profileUri) {
        var oauth2 = new ServerOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
        oauth2.setDefaultClientRegistrationId("profile-client");
        return WebClient.builder()
                .baseUrl(profileUri)
                .filter(oauth2)
                .build();
    }
}
