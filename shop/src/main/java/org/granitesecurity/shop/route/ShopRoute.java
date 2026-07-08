package org.granitesecurity.shop.route;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.granitesecurity.shop.dto.CreateCategoryRequest;
import org.granitesecurity.shop.dto.CreateProductRequest;
import org.granitesecurity.shop.dto.PlaceOrderRequest;
import org.granitesecurity.shop.handler.CatalogHandler;
import org.granitesecurity.shop.handler.GreetingsHandler;
import org.granitesecurity.shop.handler.OrderHandler;
import org.springdoc.core.annotations.RouterOperation;
import org.springdoc.core.annotations.RouterOperations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class ShopRoute {
    @Bean
    @RouterOperations({
        @RouterOperation(
            path = "/api/shop/greetings",
            method = RequestMethod.GET,
            beanClass = GreetingsHandler.class,
            beanMethod = "respondWithGreeting"
        ),
        @RouterOperation(
            path = "/api/shop/products",
            method = RequestMethod.GET,
            beanClass = CatalogHandler.class,
            beanMethod = "getAllProducts"
        ),
        @RouterOperation(
            path = "/api/shop/products/{id}",
            method = RequestMethod.GET,
            beanClass = CatalogHandler.class,
            beanMethod = "getProduct"
        ),
        @RouterOperation(
            path = "/api/shop/categories",
            method = RequestMethod.GET,
            beanClass = CatalogHandler.class,
            beanMethod = "getAllCategories"
        ),
        @RouterOperation(
            path = "/api/shop/products",
            method = RequestMethod.POST,
            beanClass = CatalogHandler.class,
            beanMethod = "createProduct",
            operation = @Operation(
                requestBody = @RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
            )
        ),
        @RouterOperation(
            path = "/api/shop/products/{id}",
            method = RequestMethod.PUT,
            beanClass = CatalogHandler.class,
            beanMethod = "updateProduct",
            operation = @Operation(
                requestBody = @RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
            )
        ),
        @RouterOperation(
            path = "/api/shop/products/{id}",
            method = RequestMethod.DELETE,
            beanClass = CatalogHandler.class,
            beanMethod = "deleteProduct"
        ),
        @RouterOperation(
            path = "/api/shop/categories",
            method = RequestMethod.POST,
            beanClass = CatalogHandler.class,
            beanMethod = "createCategory",
            operation = @Operation(
                requestBody = @RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
            )
        ),
        @RouterOperation(
            path = "/api/shop/categories/{id}",
            method = RequestMethod.PUT,
            beanClass = CatalogHandler.class,
            beanMethod = "updateCategory",
            operation = @Operation(
                requestBody = @RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
            )
        ),
        @RouterOperation(
            path = "/api/shop/categories/{id}",
            method = RequestMethod.DELETE,
            beanClass = CatalogHandler.class,
            beanMethod = "deleteCategory"
        ),
        @RouterOperation(
            path = "/api/shop/orders",
            method = RequestMethod.POST,
            beanClass = OrderHandler.class,
            beanMethod = "placeOrder",
            operation = @Operation(
                requestBody = @RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
            )
        ),
        @RouterOperation(
            path = "/api/shop/orders",
            method = RequestMethod.GET,
            beanClass = OrderHandler.class,
            beanMethod = "getOrders"
        ),
        @RouterOperation(
            path = "/api/shop/orders/all",
            method = RequestMethod.GET,
            beanClass = OrderHandler.class,
            beanMethod = "getAllOrders"
        ),
        @RouterOperation(
            path = "/api/shop/orders/{id}",
            method = RequestMethod.GET,
            beanClass = OrderHandler.class,
            beanMethod = "getOrder"
        ),
    })
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
                // "/orders/all" must be registered before "/orders/{id}"
                .GET("/api/shop/orders/all", orderHandler::getAllOrders)
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
