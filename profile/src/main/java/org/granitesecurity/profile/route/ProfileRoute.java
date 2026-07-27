package org.granitesecurity.profile.route;

import org.granitesecurity.profile.handler.AddressHandler;
import org.granitesecurity.profile.handler.ProfileHandler;
import org.granitesecurity.profile.handler.UserFileHandler;
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
            AddressHandler addressHandler,
            UserFileHandler userFileHandler) {
        return RouterFunctions.route()
                .GET("/api/profiles/me", profileHandler::getMe)
                .PUT("/api/profiles/me", profileHandler::updateMe)
                .GET("/api/profiles/me/addresses", addressHandler::listAddresses)
                .POST("/api/profiles/me/addresses", addressHandler::createAddress)
                .PUT("/api/profiles/me/addresses/{id}", addressHandler::updateAddress)
                .DELETE("/api/profiles/me/addresses/{id}", addressHandler::deleteAddress)
                .GET("/api/profiles/me/files", userFileHandler::listFiles)
                .POST("/api/profiles/me/files/presign", userFileHandler::presign)
                .POST("/api/profiles/me/files", userFileHandler::register)
                .DELETE("/api/profiles/me/files/{id}", userFileHandler::delete)
                .GET("/api/profiles/internal/{username}/addresses/{id}", addressHandler::getAddressById)
                // Admin endpoints — registered after the explicit /api/profiles/me*
                // routes so {username} never shadows "me".
                .GET("/api/profiles", profileHandler::listAll)
                .GET("/api/profiles/{username}", profileHandler::getByUsername)
                .build();
    }
}
