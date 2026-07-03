package org.granitesecurity.demokot.config

import org.granitesecurity.demokot.handler.GreetingHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.router

@Configuration(proxyBeanMethods = false)
class RouterConfig {

    @Bean
    fun greetingRoutes(handler: GreetingHandler): RouterFunction<ServerResponse> = router {
        GET("/api/hello", handler::hello)
        GET("/api/heartbeat", handler::heartbeat)
    }
}
