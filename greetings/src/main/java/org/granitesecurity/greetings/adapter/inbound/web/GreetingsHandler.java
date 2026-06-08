package org.granitesecurity.greetings.adapter.inbound.web;

import org.granitesecurity.greetings.domain.port.inbound.GreetingService;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Service
public class GreetingsHandler {

    private final GreetingService greetingService;

    public GreetingsHandler(GreetingService greetingService) {
        this.greetingService = greetingService;
    }

    public Mono<ServerResponse> respondWithGreeting(ServerRequest request) {
        return ServerResponse.ok().bodyValue(greetingService.getGreeting());
    }

    public Mono<ServerResponse> hello(ServerRequest request) {
        String name = Optional.ofNullable(request.headers().firstHeader("xname"))
                              .orElse("World");
        return ServerResponse.ok().bodyValue(greetingService.getPersonalizedGreeting(name));
    }

    public Mono<ServerResponse> welcome(ServerRequest request) {
        return ServerResponse.ok().bodyValue(greetingService.getWelcomeMessage());
    }
}
