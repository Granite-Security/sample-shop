package org.granitesecurity.shop.route;

import org.granitesecurity.shop.handler.GreetingsHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class ShopRoute {
    @Bean
    public RouterFunction<ServerResponse> shopRoutes(GreetingsHandler greetingsHandler) {
        // Define your routes here
        return RouterFunctions.route()
                .GET("/api/greetings", greetingsHandler::respondWithGreeting)
                .GET("/api/shop", request -> ServerResponse.ok().bodyValue("Welcome to the shop!"))
                .build();
    }
}
