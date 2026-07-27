package org.granitesecurity.profile.handler;

import org.granitesecurity.profile.dto.RegisterFileRequest;
import org.granitesecurity.profile.service.UserFileService;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Service
public class UserFileHandler {

    private final UserFileService userFileService;

    public UserFileHandler(UserFileService userFileService) {
        this.userFileService = userFileService;
    }

    public Mono<ServerResponse> listFiles(ServerRequest request) {
        return getUsername(request)
                .flatMapMany(userFileService::listFiles)
                .collectList()
                .flatMap(files -> ServerResponse.ok().bodyValue(files));
    }

    public Mono<ServerResponse> checkDuplicate(ServerRequest request) {
        String hash = request.queryParam("hash").orElse("");
        return getUsername(request)
                .flatMap(username -> userFileService.checkDuplicate(username, hash))
                .flatMap(response -> ServerResponse.ok().bodyValue(response));
    }

    public Mono<ServerResponse> register(ServerRequest request) {
        var bodyMono = request.bodyToMono(RegisterFileRequest.class);
        var usernameMono = getUsername(request);
        return bodyMono.zipWith(usernameMono)
                .flatMap(tuple -> userFileService.register(tuple.getT2(), tuple.getT1()))
                .flatMap(response -> ServerResponse.ok().bodyValue(response));
    }

    public Mono<ServerResponse> delete(ServerRequest request) {
        Long id = Long.valueOf(request.pathVariable("id"));
        return getUsername(request)
                .flatMap(username -> userFileService.delete(id, username))
                .then(ServerResponse.noContent().build());
    }

    private Mono<String> getUsername(ServerRequest request) {
        return request.principal()
                .cast(Authentication.class)
                .map(auth -> ((Jwt) auth.getCredentials()).getSubject());
    }
}
