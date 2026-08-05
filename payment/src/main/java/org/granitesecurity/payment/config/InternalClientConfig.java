package org.granitesecurity.payment.config;

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

/**
 * Payment was a resource server only until balance became a provider: reaching
 * balance's SCOPE_internal API needs client credentials, so this mirrors
 * profile's InternalClientConfig.
 */
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
    public WebClient balanceWebClient(ReactiveOAuth2AuthorizedClientManager authorizedClientManager,
                                      @Value("${microservices.balance.uri:http://localhost:8067}") String balanceUri) {
        var oauth2 = new ServerOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
        oauth2.setDefaultClientRegistrationId("balance-client");
        return WebClient.builder()
                .baseUrl(balanceUri)
                .filter(oauth2)
                .build();
    }
}
