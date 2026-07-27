package org.granitesecurity.storage.handler;

import org.granitesecurity.storage.dto.DeleteObjectRequest;
import org.granitesecurity.storage.dto.PresignRequest;
import org.granitesecurity.storage.service.StorageService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.stream.Collectors;

@Service
public class StorageHandler {

    private final StorageService storageService;

    public StorageHandler(StorageService storageService) {
        this.storageService = storageService;
    }

    public Mono<ServerResponse> presign(ServerRequest request) {
        var bodyMono = request.bodyToMono(PresignRequest.class);
        var authoritiesMono = getAuthorities(request);
        var usernameMono = getUsername(request);
        return Mono.zip(bodyMono, authoritiesMono, usernameMono)
                .flatMap(tuple -> storageService.presign(
                        tuple.getT1().fileName(), tuple.getT1().contentType(), tuple.getT1().scope(),
                        tuple.getT2(), tuple.getT3()))
                .flatMap(response -> ServerResponse.ok().bodyValue(response));
    }

    public Mono<ServerResponse> deleteObject(ServerRequest request) {
        var bodyMono = request.bodyToMono(DeleteObjectRequest.class);
        var authoritiesMono = getAuthorities(request);
        return bodyMono.zipWith(authoritiesMono)
                .flatMap(tuple -> storageService.deleteObject(tuple.getT1().key(), tuple.getT2()))
                .then(ServerResponse.noContent().build());
    }

    private Mono<Set<String>> getAuthorities(ServerRequest request) {
        return request.principal()
                .cast(Authentication.class)
                .map(auth -> auth.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toSet()));
    }

    private Mono<String> getUsername(ServerRequest request) {
        return request.principal()
                .cast(Authentication.class)
                .map(auth -> ((Jwt) auth.getCredentials()).getSubject());
    }
}
