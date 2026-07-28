package org.granitesecurity.profile.handler;

import org.granitesecurity.profile.dto.AvatarSourceRequest;
import org.granitesecurity.profile.dto.RegisterAvatarRequest;
import org.granitesecurity.profile.service.AvatarService;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Service
public class AvatarHandler {

    private final AvatarService avatarService;

    public AvatarHandler(AvatarService avatarService) {
        this.avatarService = avatarService;
    }

    public Mono<ServerResponse> register(ServerRequest request) {
        var bodyMono = request.bodyToMono(RegisterAvatarRequest.class);
        var usernameMono = getUsername(request);
        return bodyMono.zipWith(usernameMono)
                .flatMap(tuple -> avatarService.register(tuple.getT2(), tuple.getT1()))
                .flatMap(profile -> ServerResponse.ok().bodyValue(profile));
    }

    public Mono<ServerResponse> setSource(ServerRequest request) {
        var bodyMono = request.bodyToMono(AvatarSourceRequest.class);
        var usernameMono = getUsername(request);
        return bodyMono.zipWith(usernameMono)
                .flatMap(tuple -> avatarService.setSource(tuple.getT2(), tuple.getT1()))
                .flatMap(profile -> ServerResponse.ok().bodyValue(profile));
    }

    public Mono<ServerResponse> remove(ServerRequest request) {
        return getUsername(request)
                .flatMap(avatarService::remove)
                .flatMap(profile -> ServerResponse.ok().bodyValue(profile));
    }

    private Mono<String> getUsername(ServerRequest request) {
        return request.principal()
                .cast(Authentication.class)
                .map(auth -> ((Jwt) auth.getCredentials()).getSubject());
    }
}
