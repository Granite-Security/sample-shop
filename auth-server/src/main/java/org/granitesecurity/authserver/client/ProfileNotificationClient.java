package org.granitesecurity.authserver.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

// Blocking (servlet) RestClient, not WebClient — auth-server is Spring MVC,
// unlike the WebFlux services elsewhere in this repo. notifyPasswordChanged
// is @Async and swallows every failure: a Resend/network hiccup here must
// never affect the password-change response, which has already succeeded by
// the time this fires (see PasswordChangeService, which calls this only from
// an afterCommit transaction synchronization).
@Component
public class ProfileNotificationClient {

    private static final Logger log = LoggerFactory.getLogger(ProfileNotificationClient.class);

    private final RestClient restClient;

    public ProfileNotificationClient(RestClient.Builder builder,
                                      OAuth2AuthorizedClientManager authorizedClientManager,
                                      @Value("${microservices.profile.uri:http://localhost:8064}") String profileUri) {
        this.restClient = builder
                .baseUrl(profileUri)
                .requestInterceptor((request, body, execution) -> {
                    OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                            .withClientRegistrationId("profile-client")
                            .principal("auth-server")
                            .build();
                    OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(authorizeRequest);
                    if (authorizedClient != null) {
                        request.getHeaders().setBearerAuth(authorizedClient.getAccessToken().getTokenValue());
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    @Async
    public void notifyPasswordChanged(String username, String email) {
        try {
            restClient.post()
                    .uri("/api/profiles/internal/{username}/notify/password-changed", username)
                    .body(Map.of("email", email))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.warn("failed to notify profile of password change: {}", ex.getMessage());
        }
    }

    @Async
    public void notifyPasswordResetRequested(String username, String email, String resetLink) {
        try {
            restClient.post()
                    .uri("/api/profiles/internal/{username}/notify/password-reset-requested", username)
                    .body(Map.of("email", email, "resetLink", resetLink))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.warn("failed to notify profile of password reset request: {}", ex.getMessage());
        }
    }
}
