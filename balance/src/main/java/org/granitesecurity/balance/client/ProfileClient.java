package org.granitesecurity.balance.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * balance → profile, to check a transfer recipient exists (D10).
 *
 * <p>Money sent to a username nobody owns would sit in an account nobody can sign
 * in to, so this runs before the movement, not after.
 */
@Service
public class ProfileClient {

    private static final Logger log = LoggerFactory.getLogger(ProfileClient.class);

    private final WebClient profileWebClient;

    public ProfileClient(@Qualifier("profileWebClient") WebClient profileWebClient) {
        this.profileWebClient = profileWebClient;
    }

    /**
     * @return the username as profile knows it
     * @throws ResponseStatusException 404 if no such user, 502 if profile cannot be reached
     */
    public Mono<String> requireUser(String username) {
        return profileWebClient.get()
                .uri("/api/profiles/internal/users/{username}", username)
                .retrieve()
                .bodyToMono(ProfileView.class)
                .map(ProfileView::username)
                .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.error(
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user: " + username)))
                .onErrorResume(e -> {
                    if (e instanceof ResponseStatusException) {
                        return Mono.error(e);
                    }
                    // Fail closed: we cannot confirm the recipient exists, so we do
                    // not move money and say so plainly.
                    log.error("Could not reach profile to validate '{}'", username, e);
                    return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "Cannot verify the recipient right now, please try again"));
                });
    }

    /** Only the field we need; profile's response carries more. */
    private record ProfileView(String username) {
    }
}
