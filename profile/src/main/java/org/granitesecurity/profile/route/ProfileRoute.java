package org.granitesecurity.profile.route;

import org.granitesecurity.profile.handler.AddressHandler;
import org.granitesecurity.profile.handler.AdminUserHandler;
import org.granitesecurity.profile.handler.AvatarHandler;
import org.granitesecurity.profile.handler.MessageHandler;
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
            UserFileHandler userFileHandler,
            AvatarHandler avatarHandler,
            AdminUserHandler adminUserHandler,
            MessageHandler messageHandler) {
        return RouterFunctions.route()
                .GET("/api/profiles/me", profileHandler::getMe)
                .PUT("/api/profiles/me", profileHandler::updateMe)
                .PUT("/api/profiles/me/avatar", avatarHandler::register)
                .PUT("/api/profiles/me/avatar/source", avatarHandler::setSource)
                .DELETE("/api/profiles/me/avatar", avatarHandler::remove)
                .GET("/api/profiles/me/addresses", addressHandler::listAddresses)
                .POST("/api/profiles/me/addresses", addressHandler::createAddress)
                .PUT("/api/profiles/me/addresses/{id}", addressHandler::updateAddress)
                .DELETE("/api/profiles/me/addresses/{id}", addressHandler::deleteAddress)
                .GET("/api/profiles/me/files", userFileHandler::listFiles)
                .GET("/api/profiles/me/files/duplicate", userFileHandler::checkDuplicate)
                .POST("/api/profiles/me/files", userFileHandler::register)
                .DELETE("/api/profiles/me/files/{id}", userFileHandler::delete)
                // Messaging (docs/users/messaging.md). "recipients" and "unread-count"
                // are registered before the {id} routes — otherwise they parse as a
                // message id and fail on Long.valueOf, the same shadowing trap the
                // admin routes below document.
                .GET("/api/profiles/me/messages/recipients", messageHandler::searchRecipients)
                .GET("/api/profiles/me/messages/unread-count", messageHandler::unreadCount)
                .GET("/api/profiles/me/messages", messageHandler::list)
                .POST("/api/profiles/me/messages", messageHandler::send)
                .GET("/api/profiles/me/messages/{id}", messageHandler::get)
                .POST("/api/profiles/me/messages/{id}/read", messageHandler::markRead)
                .DELETE("/api/profiles/me/messages/{id}", messageHandler::delete)
                // Service-to-service lookup: balance calls this to check a transfer
                // recipient exists before moving money to them. Registered before
                // the {username} internal route below so "users" is never read as
                // a username.
                .GET("/api/profiles/internal/users/{username}", profileHandler::getByUsername)
                .GET("/api/profiles/internal/{username}/addresses/{id}", addressHandler::getAddressById)
                // User administration — before the {username} routes below, since
                // "admin" would otherwise be read as a username.
                .GET("/api/profiles/admin/users", adminUserHandler::listUsers)
                .POST("/api/profiles/admin/users/{username}/block", adminUserHandler::blockUser)
                .POST("/api/profiles/admin/users/{username}/unblock", adminUserHandler::unblockUser)
                .DELETE("/api/profiles/admin/users/{username}", adminUserHandler::deleteUser)
                .GET("/api/profiles/admin/orphans", adminUserHandler::orphanReport)
                // Admin endpoints — registered after the explicit /api/profiles/me*
                // routes so {username} never shadows "me".
                .GET("/api/profiles", profileHandler::listAll)
                .GET("/api/profiles/{username}", profileHandler::getByUsername)
                .build();
    }
}
