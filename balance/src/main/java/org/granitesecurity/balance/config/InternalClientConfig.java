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
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

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

    /**
     * Pooled connections outlive the pods behind them. When profile is redeployed
     * its IP goes away, but this client keeps the dead connections and fails the
     * next request through each one — reactor-netty cannot retry, because the
     * headers are already sent ("the request cannot be retried as the
     * headers/body were sent"). Observed here as an intermittent 503 on the first
     * transfer after a profile rollout.
     *
     * <p>Same fix, and same numbers, as the gateway's httpclient pool settings —
     * see the comment in gateway/application.yaml. max-idle-time bounds an unused
     * connection; max-life-time bounds a busy one, which idle eviction alone
     * never reaches.
     */
    @Bean
    public ConnectionProvider balanceConnectionProvider() {
        return ConnectionProvider.builder("balance-internal")
                .maxIdleTime(Duration.ofSeconds(15))
                .maxLifeTime(Duration.ofSeconds(60))
                .evictInBackground(Duration.ofSeconds(15))
                .build();
    }

    @Bean
    public WebClient profileWebClient(ReactiveOAuth2AuthorizedClientManager authorizedClientManager,
                                      ConnectionProvider balanceConnectionProvider,
                                      @Value("${microservices.profile.uri}") String profileUri) {
        var oauth2 = new ServerOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
        oauth2.setDefaultClientRegistrationId("profile-client");
        return WebClient.builder()
                .baseUrl(profileUri)
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create(balanceConnectionProvider)))
                .filter(oauth2)
                .build();
    }
}
