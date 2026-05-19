package org.granitesecurity.greetings.handler;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.stream.Collectors;

@Service
public class SecuredGreetingsHandler {
    public Mono<ServerResponse> respondWithSecuredGreeting(ServerRequest serverRequest) {
        return serverRequest.principal()
                .cast(Authentication.class)
                .flatMap(auth -> {
                    String name = auth.getName();
                    String authorities = auth.getAuthorities().stream()
                            .map(GrantedAuthority::getAuthority)
                            .collect(Collectors.joining(", "));

                    return ServerResponse.ok()
                            .bodyValue("Hello, " + name + "! This is a secured greeting. Your grants and roles: [" + authorities + "]");
                });

    }
}
