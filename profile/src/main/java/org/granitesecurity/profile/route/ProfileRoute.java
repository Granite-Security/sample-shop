package org.granitesecurity.profile.route;

import org.granitesecurity.profile.handler.AddressHandler;
import org.granitesecurity.profile.handler.ProfileHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class ProfileRoute {

    @Bean
    public RouterFunction<ServerResponse> profileRoutes(
            ProfileHandler profileHandler,
            AddressHandler addressHandler) {
        return RouterFunctions.route()
                .GET("/api/profiles/me", profileHandler::getMe)
                .PUT("/api/profiles/me", profileHandler::updateMe)
                .GET("/api/profiles/me/addresses", addressHandler::listAddresses)
                .POST("/api/profiles/me/addresses", addressHandler::createAddress)
                .PUT("/api/profiles/me/addresses/{id}", addressHandler::updateAddress)
                .DELETE("/api/profiles/me/addresses/{id}", addressHandler::deleteAddress)
                .GET("/api/profiles/internal/{username}/addresses/{id}", addressHandler::getAddressById)
                .build();
    }
}
