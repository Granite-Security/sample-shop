package org.granitesecurity.greetings.routes;

import org.granitesecurity.greetings.handler.GreetingsHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Configuration
public class GreetingsRoute {



    @Bean
    RouterFunction<ServerResponse> greetingsRouter(GreetingsHandler greetingsHandler) {
        return RouterFunctions.route()
                .GET("/api/greetings", greetingsHandler::respondWithGreeting)
                .GET("/api/secured", request -> ServerResponse.ok().bodyValue("This is a secured endpoint"))
                .build();

    }
}
