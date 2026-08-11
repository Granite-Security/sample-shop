package org.granitesecurity.shop.route;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.granitesecurity.shop.dto.CreateCategoryRequest;
import org.granitesecurity.shop.dto.CreateProductRequest;
import org.granitesecurity.shop.dto.PlaceOrderRequest;
import org.granitesecurity.shop.handler.AdminRevenueHandler;
import org.granitesecurity.shop.handler.CatalogHandler;
import org.granitesecurity.shop.handler.GreetingsHandler;
import org.granitesecurity.shop.handler.OrderHandler;
import org.granitesecurity.shop.handler.PackagingAdminHandler;
import org.granitesecurity.shop.handler.PackagingHandler;
import org.granitesecurity.shop.handler.UserOrderHandler;
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
        @RouterOperation(
            path = "/api/shop/orders/{id}/refund",
            method = RequestMethod.POST,
            beanClass = OrderHandler.class,
            beanMethod = "refundOrder"
        ),
        @RouterOperation(
            path = "/api/shop/admin/revenue",
            method = RequestMethod.GET,
            beanClass = AdminRevenueHandler.class,
            beanMethod = "getRevenue"
        ),
        @RouterOperation(
            path = "/api/shop/admin/revenue/currencies",
            method = RequestMethod.GET,
            beanClass = AdminRevenueHandler.class,
            beanMethod = "getRevenueCurrencies"
        ),
        @RouterOperation(
            path = "/api/shop/packaging/quote",
            method = RequestMethod.POST,
            beanClass = PackagingHandler.class,
            beanMethod = "quote",
            operation = @Operation(
                requestBody = @RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
            )
        ),
        @RouterOperation(
            path = "/api/shop/admin/packaging/groups",
            method = RequestMethod.GET,
            beanClass = PackagingAdminHandler.class,
            beanMethod = "listGroups"
        ),
        @RouterOperation(
            path = "/api/shop/admin/packaging/options",
            method = RequestMethod.GET,
            beanClass = PackagingAdminHandler.class,
            beanMethod = "listOptions"
        ),
        @RouterOperation(
            path = "/api/shop/users/{username}/orders",
            method = RequestMethod.GET,
            beanClass = UserOrderHandler.class,
            beanMethod = "getOrdersByUsername"
        ),
    })
    public RouterFunction<ServerResponse> shopRoutes(
            GreetingsHandler greetingsHandler,
            CatalogHandler catalogHandler,
            OrderHandler orderHandler,
            UserOrderHandler userOrderHandler,
            AdminRevenueHandler adminRevenueHandler,
            PackagingHandler packagingHandler,
            PackagingAdminHandler packagingAdminHandler) {
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
                .POST("/api/shop/orders/{id}/refund", orderHandler::refundOrder)

                // Orders by username — a separate root, because "{id}" under
                // /orders is an order id and a "{username}" segment there would
                // shadow it (same trap as "/orders/all" above)
                .GET("/api/shop/users/{username}/orders", userOrderHandler::getOrdersByUsername)
                .GET("/api/shop/internal/users/{username}/purge-eligibility",
                        userOrderHandler::getPurgeEligibility)
                .DELETE("/api/shop/internal/users/{username}/orders", userOrderHandler::purgeOrders)
                .GET("/api/shop/internal/orders/owners", userOrderHandler::listOrderOwners)
                .POST("/api/shop/internal/orders/unknown", userOrderHandler::findUnknownOrderIds)

                // Packaging — authenticated. A POST because the cart is the question,
                // and a cart does not fit in a query string. Nothing is stored: it
                // prices boxes for a cart that may never become an order.
                .POST("/api/shop/packaging/quote", packagingHandler::quote)

                // Admin — reports. Read-only: nothing under /admin/revenue has a side
                // effect (docs/finance/accounting.md D20). The packaging routes below
                // do write, which is why the read-only rule is stated per-subtree here
                // rather than for /admin as a whole.
                .GET("/api/shop/admin/revenue", adminRevenueHandler::getRevenue)
                .GET("/api/shop/admin/revenue/currencies", adminRevenueHandler::getRevenueCurrencies)

                // Admin — packaging (docs/packaging/packaging.md step 7)
                .GET("/api/shop/admin/packaging/groups", packagingAdminHandler::listGroups)
                .POST("/api/shop/admin/packaging/groups", packagingAdminHandler::createGroup)
                .PUT("/api/shop/admin/packaging/groups/{id}", packagingAdminHandler::updateGroup)
                .GET("/api/shop/admin/packaging/options", packagingAdminHandler::listOptions)
                .POST("/api/shop/admin/packaging/options", packagingAdminHandler::createOption)
                .PUT("/api/shop/admin/packaging/options/{id}", packagingAdminHandler::updateOption)
                // Capacity belongs to the pairing, so it is addressed under the group
                // and names the option — never under the option alone, which has no
                // capacity of its own.
                .PUT("/api/shop/admin/packaging/groups/{id}/options", packagingAdminHandler::setCapacity)
                .DELETE("/api/shop/admin/packaging/groups/{id}/options/{optionId}",
                        packagingAdminHandler::removeCapacity)

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
