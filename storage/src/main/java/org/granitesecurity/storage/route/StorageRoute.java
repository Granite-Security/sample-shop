package org.granitesecurity.storage.route;

import org.granitesecurity.storage.handler.HealthHandler;
import org.granitesecurity.storage.handler.StorageHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class StorageRoute {

    @Bean
    public RouterFunction<ServerResponse> storageRoutes(
            StorageHandler storageHandler,
            HealthHandler healthHandler) {
        return RouterFunctions.route()
                .GET("/actuator/health", healthHandler::health)
                .POST("/api/storage/presign", storageHandler::presign)
                .DELETE("/api/storage/objects", storageHandler::deleteObject)
                .build();
    }
}
