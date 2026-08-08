package org.granitesecurity.profile.handler;

import org.granitesecurity.profile.dto.ContactRequest;
import org.granitesecurity.profile.service.ContactService;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * The public contact form (docs/users/messaging.md §11).
 *
 * <p>Unlike {@link MessageHandler}, this route is <strong>not</strong> under
 * {@code /api/profiles/me/**} and is <strong>not</strong> authenticated — that is the
 * whole point, and it is why it lives in its own class instead of as an eighth method
 * next to seven that can all assume a signed-in caller. {@code ProfileSec} permits
 * {@code POST /api/profiles/contact} explicitly, ahead of the
 * {@code /api/profiles/**} authenticated rule.
 *
 * <p>The sender is still never read from the request body. It is the JWT subject when
 * there is one and null when there is not; the form's own "from" field is a display
 * convenience the browser fills in, not an input the server trusts.
 */
@Service
public class ContactHandler {

    private final ContactService contactService;

    public ContactHandler(ContactService contactService) {
        this.contactService = contactService;
    }

    public Mono<ServerResponse> submit(ServerRequest request) {
        return request.bodyToMono(ContactRequest.class)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "A message is required")))
                .flatMap(body -> authenticatedUsername(request)
                        .flatMap(sender -> contactService.submit(sender, body))
                        // No principal at all: an anonymous visitor, which is allowed
                        // here and nowhere else in this service.
                        .switchIfEmpty(Mono.defer(() -> contactService.submit(null, body))))
                .flatMap(response -> ServerResponse.status(HttpStatus.CREATED).bodyValue(response));
    }

    /**
     * Empty when nobody is signed in. Spring Security installs an anonymous
     * authentication on a permitAll exchange, so "is there a principal" is not the
     * question — "is it a bearer token" is.
     *
     * <p>Note this cannot be reached with a <em>bad</em> token: the resource-server
     * filter rejects an expired or unverifiable JWT with a 401 before routing, even on
     * a permitted path. Sending no token is anonymous; sending a broken one is an error.
     */
    private static Mono<String> authenticatedUsername(ServerRequest request) {
        return request.principal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(token -> token.getToken().getSubject());
    }
}
