package org.granitesecurity.demokot.handler

import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

@Component
class GreetingHandler {

    fun hello(request: ServerRequest): Mono<ServerResponse> =
        ServerResponse.ok().bodyValue("Welcome to our microservice!")

    fun heartbeat(request: ServerRequest): Mono<ServerResponse> =
        ServerResponse.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(
                Flux.interval(Duration.ofSeconds(10))
                    .map { "heartbeat" }
                    .take(15),
                String::class.java
            )
}
