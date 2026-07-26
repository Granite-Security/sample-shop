package org.granitesecurity.profile.handler;

import org.granitesecurity.profile.dto.UpdateProfileRequest;
import org.granitesecurity.profile.service.ProfileService;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Service
public class ProfileHandler {

    private final ProfileService profileService;

    public ProfileHandler(ProfileService profileService) {
        this.profileService = profileService;
    }

    public Mono<ServerResponse> getMe(ServerRequest request) {
        return getUsername(request)
                .flatMap(profileService::getProfile)
                .flatMap(profile -> ServerResponse.ok().bodyValue(profile));
    }

    public Mono<ServerResponse> listAll(ServerRequest request) {
        return ServerResponse.ok().body(profileService.listAll(), org.granitesecurity.profile.dto.ProfileResponse.class);
    }

    public Mono<ServerResponse> getByUsername(ServerRequest request) {
        String username = request.pathVariable("username");
        return profileService.getByUsername(username)
                .flatMap(profile -> ServerResponse.ok().bodyValue(profile));
    }

    public Mono<ServerResponse> updateMe(ServerRequest request) {
        var bodyMono = request.bodyToMono(UpdateProfileRequest.class);
        var usernameMono = getUsername(request);
        return bodyMono.zipWith(usernameMono)
                .flatMap(tuple -> profileService.updateProfile(tuple.getT2(), tuple.getT1()))
                .flatMap(profile -> ServerResponse.ok().bodyValue(profile));
    }

    private Mono<String> getUsername(ServerRequest request) {
        return request.principal()
                .cast(Authentication.class)
                .map(auth -> ((Jwt) auth.getCredentials()).getSubject());
    }
}
