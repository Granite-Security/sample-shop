package org.granitesecurity.shop.handler;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Service
public class GreetingsHandler {

    public Mono<ServerResponse> respondWithGreeting(ServerRequest serverRequest) {
        return ServerResponse.ok().bodyValue("Hello, welcome to the shop!");
    }
}
