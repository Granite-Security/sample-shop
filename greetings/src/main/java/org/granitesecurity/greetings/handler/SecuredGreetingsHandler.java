package org.granitesecurity.greetings.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.stream.Collectors;

@Slf4j
@Service
public class SecuredGreetingsHandler {
    public Mono<ServerResponse> respondWithSecuredGreeting(ServerRequest serverRequest) {

        return serverRequest.principal()
                .cast(Authentication.class)
                .flatMap(auth -> {
                    String name = auth.getName();
                    Jwt credentials = (Jwt) auth.getCredentials();
                    log.info("Authenticated user: {}, credentials: {}", name, credentials.toString());
                    log.info("headers {}", credentials.getHeaders());
                    log.info("claims {}", credentials.getClaims());
                    Object details = auth.getDetails();
                    log.info("Authenticated user details: {}", details);


                    String authorities = auth.getAuthorities().stream()
                            .map(GrantedAuthority::getAuthority)
                            .collect(Collectors.joining(", "));

                    return ServerResponse.ok()
                            .bodyValue("Hello, " + name + "! This is a secured greeting. Your grants and roles: [" + authorities + "]" + credentials.toString());
                });

    }
}
