package org.granitesecurity.profile.route;

import org.granitesecurity.profile.handler.AddressHandler;
import org.granitesecurity.profile.handler.AdminUserHandler;
import org.granitesecurity.profile.handler.AvatarHandler;
import org.granitesecurity.profile.handler.ContactHandler;
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
            MessageHandler messageHandler,
            ContactHandler contactHandler) {
        return RouterFunctions.route()
                // The public contact form (docs/users/messaging.md §11) — the one
                // route in this service an unauthenticated caller may reach, and the
                // one ProfileSec permits by method as well as path. It does not
                // collide with GET /api/profiles/{username} below (different method),
                // but it is registered first so the exception is visible where the
                // routes are read.
                .POST("/api/profiles/contact", contactHandler::submit)
                // The public profile read (docs/profile/public-profile.md). Registered
                // before "/api/profiles/{username}" below, since "public" is a legal
                // value for that wildcard. ProfileSec permits this path by method as
                // well, and it is the only readable route here without a token.
                .GET("/api/profiles/public/{handle}", profileHandler::getPublicProfile)
                // Files the owner published to that profile (§11). Same permitAll
                // rule covers it: ProfileSec permits GET /api/profiles/public/**.
                .GET("/api/profiles/public/{handle}/files", userFileHandler::listPublicFiles)
                .GET("/api/profiles/me", profileHandler::getMe)
                // Separate from PUT /me, which overwrites every field it is given, and
                // which has no 409 to report (D5). "handle/available" is registered
                // before the {id}-shaped routes for the usual shadowing reason.
                .GET("/api/profiles/me/handle/available", profileHandler::checkHandle)
                .PUT("/api/profiles/me/handle", profileHandler::setHandle)
                .PUT("/api/profiles/me/visibility", profileHandler::setVisibility)
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
                .PUT("/api/profiles/me/files/{id}/share", userFileHandler::setShared)
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
                .POST("/api/profiles/admin/users/{username}/unpublish", adminUserHandler::unpublishUser)
                // Admin endpoints — registered after the explicit /api/profiles/me*
                // routes so {username} never shadows "me".
                .GET("/api/profiles", profileHandler::listAll)
                .GET("/api/profiles/{username}", profileHandler::getByUsername)
                .build();
    }
}
