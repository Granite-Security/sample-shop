package org.granitesecurity.shop.route;

import org.granitesecurity.shop.handler.CatalogHandler;
import org.granitesecurity.shop.handler.GreetingsHandler;
import org.granitesecurity.shop.handler.OrderHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class ShopRoute {
    @Bean
    public RouterFunction<ServerResponse> shopRoutes(
            GreetingsHandler greetingsHandler,
            CatalogHandler catalogHandler,
            OrderHandler orderHandler) {
        return RouterFunctions.route()
                .GET("/api/shop/greetings", greetingsHandler::respondWithGreeting)

                // Catalog — public read
                .GET("/api/shop/products", catalogHandler::getAllProducts)
                .GET("/api/shop/products/{id}", catalogHandler::getProduct)
                .GET("/api/shop/categories", catalogHandler::getAllCategories)

                // Orders — authenticated
                .POST("/api/shop/orders", orderHandler::placeOrder)
                .GET("/api/shop/orders", orderHandler::getOrders)
                .GET("/api/shop/orders/{id}", orderHandler::getOrder)

                // Admin — products
                .POST("/api/shop/products", catalogHandler::createProduct)
                .PUT("/api/shop/products/{id}", catalogHandler::updateProduct)
                .DELETE("/api/shop/products/{id}", catalogHandler::deleteProduct)

                // Admin — categories
                .POST("/api/shop/categories", catalogHandler::createCategory)
                .PUT("/api/shop/categories/{id}", catalogHandler::updateCategory)
                .DELETE("/api/shop/categories/{id}", catalogHandler::deleteCategory)

                .build();
    }
}
