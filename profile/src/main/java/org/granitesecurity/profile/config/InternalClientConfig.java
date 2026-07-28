package org.granitesecurity.profile.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

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
    public WebClient storageWebClient(ReactiveOAuth2AuthorizedClientManager authorizedClientManager,
                                       @Value("${microservices.storage.uri}") String storageUri) {
        var oauth2 = new ServerOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
        oauth2.setDefaultClientRegistrationId("storage-client");
        return WebClient.builder()
                .baseUrl(storageUri)
                .filter(oauth2)
                .build();
    }

    @Bean
    public WebClient shopWebClient(ReactiveOAuth2AuthorizedClientManager authorizedClientManager,
                                   @Value("${microservices.shop.uri}") String shopUri) {
        var oauth2 = new ServerOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
        oauth2.setDefaultClientRegistrationId("shop-client");
        return WebClient.builder()
                .baseUrl(shopUri)
                .filter(oauth2)
                .build();
    }

    @Bean
    public WebClient paymentWebClient(ReactiveOAuth2AuthorizedClientManager authorizedClientManager,
                                      @Value("${microservices.payment.uri}") String paymentUri) {
        var oauth2 = new ServerOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
        oauth2.setDefaultClientRegistrationId("shop-client");
        return WebClient.builder().baseUrl(paymentUri).filter(oauth2).build();
    }

    @Bean
    public WebClient deliveryWebClient(ReactiveOAuth2AuthorizedClientManager authorizedClientManager,
                                       @Value("${microservices.delivery.uri}") String deliveryUri) {
        var oauth2 = new ServerOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
        oauth2.setDefaultClientRegistrationId("shop-client");
        return WebClient.builder().baseUrl(deliveryUri).filter(oauth2).build();
    }

    /**
     * A separate registration from the two above on purpose: this one carries
     * the identity-admin credentials, which only profile holds, and it is the
     * only way to reach auth-server's user-administration API. Reusing
     * internal-service here would put the identity store inside the blast
     * radius of any internal-service leak (docs/users/blocking-users.md §3.1).
     */
    @Bean
    public WebClient identityAdminWebClient(ReactiveOAuth2AuthorizedClientManager authorizedClientManager,
                                            @Value("${microservices.auth-server.uri}") String authServerUri) {
        var oauth2 = new ServerOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
        oauth2.setDefaultClientRegistrationId("identity-admin-client");
        return WebClient.builder()
                .baseUrl(authServerUri)
                .filter(oauth2)
                .build();
    }
}
