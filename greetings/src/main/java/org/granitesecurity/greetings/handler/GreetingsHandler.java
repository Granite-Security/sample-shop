package org.granitesecurity.greetings.handler;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Service
public class GreetingsHandler {

    public Mono<ServerResponse> respondWithGreeting(ServerRequest request) {
        return ServerResponse.ok().bodyValue("Hello, World!");
    }
}
