package org.granitesecurity.storage.handler;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
public class HealthHandler {

    public Mono<ServerResponse> health(ServerRequest request) {
        return ServerResponse.ok().bodyValue(Map.of("status", "UP"));
    }
}
