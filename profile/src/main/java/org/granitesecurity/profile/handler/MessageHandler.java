package org.granitesecurity.profile.handler;

import org.granitesecurity.profile.dto.SendMessageRequest;
import org.granitesecurity.profile.service.MessageService;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * All routes here sit under /api/profiles/me/**, which ProfileSec already requires
 * authentication for — the caller is the JWT subject and there is no request field
 * that can change that (docs/users/messaging.md §5).
 */
@Service
public class MessageHandler {

    private final MessageService messageService;

    public MessageHandler(MessageService messageService) {
        this.messageService = messageService;
    }

    public Mono<ServerResponse> send(ServerRequest request) {
        return getUsername(request)
                .flatMap(sender -> request.bodyToMono(SendMessageRequest.class)
                        .switchIfEmpty(Mono.error(new org.springframework.web.server.ResponseStatusException(
                                org.springframework.http.HttpStatus.BAD_REQUEST, "A message is required")))
                        .flatMap(body -> messageService.send(sender, body)))
                .flatMap(message -> ServerResponse.status(201).bodyValue(message));
    }

    public Mono<ServerResponse> list(ServerRequest request) {
        String box = request.queryParam("box").orElse("inbox");
        int page = intParam(request, "page", 0);
        int size = intParam(request, "size", 20);

        return getUsername(request)
                .flatMapMany(username -> messageService.list(username, box, page, size))
                .collectList()
                .flatMap(messages -> ServerResponse.ok().bodyValue(messages));
    }

    public Mono<ServerResponse> unreadCount(ServerRequest request) {
        return getUsername(request)
                .flatMap(messageService::unreadCount)
                .flatMap(count -> ServerResponse.ok().bodyValue(Map.of("count", count)));
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        Long id = Long.valueOf(request.pathVariable("id"));
        return getUsername(request)
                .flatMap(username -> messageService.get(id, username))
                .flatMap(message -> ServerResponse.ok().bodyValue(message));
    }

    public Mono<ServerResponse> markRead(ServerRequest request) {
        Long id = Long.valueOf(request.pathVariable("id"));
        return getUsername(request)
                .flatMap(username -> messageService.markRead(id, username))
                .then(ServerResponse.noContent().build());
    }

    public Mono<ServerResponse> delete(ServerRequest request) {
        Long id = Long.valueOf(request.pathVariable("id"));
        return getUsername(request)
                .flatMap(username -> messageService.delete(id, username))
                .then(ServerResponse.noContent().build());
    }

    public Mono<ServerResponse> searchRecipients(ServerRequest request) {
        String query = request.queryParam("q").orElse("");
        return getUsername(request)
                .flatMapMany(caller -> messageService.searchRecipients(query, caller))
                .collectList()
                .flatMap(recipients -> ServerResponse.ok().bodyValue(recipients));
    }

    private static int intParam(ServerRequest request, String name, int fallback) {
        try {
            return request.queryParam(name).map(Integer::parseInt).orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private Mono<String> getUsername(ServerRequest request) {
        return request.principal()
                .cast(Authentication.class)
                .map(auth -> ((Jwt) auth.getCredentials()).getSubject());
    }
}
