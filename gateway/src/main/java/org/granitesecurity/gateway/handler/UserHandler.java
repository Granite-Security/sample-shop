package org.granitesecurity.gateway.handler;

import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
public class UserHandler {

    public Mono<ServerResponse> me(ServerRequest request) {
        return request.principal()
                .filter(p -> p instanceof OAuth2AuthenticationToken)
                .cast(OAuth2AuthenticationToken.class)
                .flatMap(auth -> {
                    var principal = auth.getPrincipal();
                    Map<String, Object> claims;
                    String name;
                    if (principal instanceof OidcUser oidc) {
                        claims = oidc.getClaims();
                        name = oidc.getPreferredUsername() != null
                                ? oidc.getPreferredUsername()
                                : oidc.getSubject();
                    } else {
                        claims = Map.of("sub", principal.getName());
                        name = principal.getName();
                    }
                    return ServerResponse.ok().bodyValue(Map.of(
                            "authenticated", true,
                            "name", name,
                            "claims", claims
                    ));
                })
                .switchIfEmpty(
                        ServerResponse.ok().bodyValue(Map.of("authenticated", false))
                );
    }

}
