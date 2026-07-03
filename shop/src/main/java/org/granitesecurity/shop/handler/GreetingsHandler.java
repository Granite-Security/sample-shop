package org.granitesecurity.shop.handler;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Service
public class GreetingsHandler {

    @Operation(operationId = "respondWithGreeting", summary = "Greeting", description = "Public greeting endpoint")
    @ApiResponse(responseCode = "200", description = "Greeting message")
    public Mono<ServerResponse> respondWithGreeting(ServerRequest serverRequest) {
        return ServerResponse.ok().bodyValue("Hello, welcome to the shop!");
    }
}
