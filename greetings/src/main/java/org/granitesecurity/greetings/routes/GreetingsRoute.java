package org.granitesecurity.greetings.routes;

import org.granitesecurity.greetings.handler.GreetingsHandler;
import org.granitesecurity.greetings.handler.SecuredGreetingsHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class GreetingsRoute {


    @Bean
    RouterFunction<ServerResponse> greetingsRouter(GreetingsHandler greetingsHandler, SecuredGreetingsHandler securedGreetingsHandler) {
        return RouterFunctions.route()
                .GET("/api/greetings", greetingsHandler::respondWithGreeting)
                .GET("/api/secured", securedGreetingsHandler::respondWithSecuredGreeting)
                .build();

    }
}
