package org.granitesecurity.profile.client;

import org.granitesecurity.profile.dto.AuthUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * profile → auth-server, over the identity.admin scope. This is the "execute"
 * half of the split in docs/users/blocking-users.md §3: profile decides and
 * authorizes, auth-server performs the identity change.
 *
 * <p>Uses the dedicated identity-admin client, never internal-service — a leak
 * of the shared internal credentials must not reach the identity store (§3.1).
 */
@Service
public class IdentityAdminClient {

    private static final Logger log = LoggerFactory.getLogger(IdentityAdminClient.class);

    private final WebClient identityAdminWebClient;

    public IdentityAdminClient(@Qualifier("identityAdminWebClient") WebClient identityAdminWebClient) {
        this.identityAdminWebClient = identityAdminWebClient;
    }

    public Flux<AuthUser> listUsers() {
        return identityAdminWebClient.get()
                .uri("/api/internal/users")
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapError)
                .bodyToFlux(AuthUser.class);
    }

    public Mono<AuthUser> block(String username, String actor) {
        return identityAdminWebClient.post()
                .uri("/api/internal/users/{username}/block", username)
                .bodyValue(Map.of("actor", actor))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapError)
                .bodyToMono(AuthUser.class);
    }

    public Mono<AuthUser> unblock(String username) {
        return identityAdminWebClient.post()
                .uri("/api/internal/users/{username}/unblock", username)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapError)
                .bodyToMono(AuthUser.class);
    }

    public Mono<Void> delete(String username) {
        return identityAdminWebClient.method(HttpMethod.DELETE)
                .uri("/api/internal/users/{username}", username)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapError)
                .toBodilessEntity()
                .then();
    }

    // auth-server's own 404/409 are meaningful to the admin (no such user, not
    // blocked), so they are passed through rather than flattened into a 502.
    private Mono<? extends Throwable> mapError(ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> {
                    log.warn("auth-server request failed: status={} body={}", status, body);
                    if (status.value() == HttpStatus.NOT_FOUND.value()
                            || status.value() == HttpStatus.CONFLICT.value()) {
                        return Mono.error(new ResponseStatusException(status, body));
                    }
                    return Mono.error(new ResponseStatusException(
                            HttpStatus.BAD_GATEWAY, "auth-server request failed"));
                });
    }
}
