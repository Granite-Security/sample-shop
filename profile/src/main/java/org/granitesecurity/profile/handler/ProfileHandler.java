package org.granitesecurity.profile.handler;

import org.granitesecurity.profile.dto.HandleRequest;
import org.granitesecurity.profile.dto.UpdateProfileRequest;
import org.granitesecurity.profile.dto.VisibilityRequest;
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

    // The only endpoint that reads the `picture` claim: it is the one a signed-in
    // user hits on every account page, which is what keeps the cached Google
    // picture fresh without any polling (docs/users/user-pic.md D1).
    public Mono<ServerResponse> getMe(ServerRequest request) {
        return getJwt(request)
                .flatMap(jwt -> profileService.getProfile(jwt.getSubject(), jwt.getClaimAsString("picture")))
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

    /**
     * The one endpoint in this service an anonymous caller may read
     * (docs/profile/public-profile.md step 5). It reads no principal — do not add one.
     */
    public Mono<ServerResponse> getPublicProfile(ServerRequest request) {
        return profileService.getPublishedByHandle(request.pathVariable("handle"))
                .flatMap(profile -> ServerResponse.ok().bodyValue(profile));
    }

    public Mono<ServerResponse> setHandle(ServerRequest request) {
        return request.bodyToMono(HandleRequest.class)
                .zipWith(getUsername(request))
                .flatMap(tuple -> profileService.setHandle(tuple.getT2(), tuple.getT1().handle()))
                .flatMap(profile -> ServerResponse.ok().bodyValue(profile));
    }

    public Mono<ServerResponse> setVisibility(ServerRequest request) {
        return request.bodyToMono(VisibilityRequest.class)
                .zipWith(getUsername(request))
                .flatMap(tuple -> profileService.setVisibility(tuple.getT2(), tuple.getT1().publicProfile()))
                .flatMap(profile -> ServerResponse.ok().bodyValue(profile));
    }

    public Mono<ServerResponse> checkHandle(ServerRequest request) {
        String candidate = request.queryParam("handle").orElse("");
        return getUsername(request)
                .flatMap(username -> profileService.checkHandle(username, candidate))
                .flatMap(availability -> ServerResponse.ok().bodyValue(availability));
    }

    private Mono<String> getUsername(ServerRequest request) {
        return getJwt(request).map(Jwt::getSubject);
    }

    private Mono<Jwt> getJwt(ServerRequest request) {
        return request.principal()
                .cast(Authentication.class)
                .map(auth -> (Jwt) auth.getCredentials());
    }
}
