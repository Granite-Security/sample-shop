package org.granitesecurity.shop.handler;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.granitesecurity.shop.dto.CategoryResponse;
import org.granitesecurity.shop.dto.CreateCategoryRequest;
import org.granitesecurity.shop.dto.CreateProductRequest;
import org.granitesecurity.shop.dto.ProductResponse;
import org.granitesecurity.shop.service.CatalogService;
import org.granitesecurity.shop.service.ShopException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Service
public class CatalogHandler {

    private final CatalogService catalogService;

    public CatalogHandler(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Operation(operationId = "getAllProducts", summary = "List all products", description = "Public catalog listing")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "List of products"))
    public Mono<ServerResponse> getAllProducts(ServerRequest request) {
        return ServerResponse.ok().body(catalogService.getAllProducts(), ProductResponse.class);
    }

    @Operation(operationId = "getProduct", summary = "Get a product by ID", description = "Public product detail")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Product found"),
            @ApiResponse(responseCode = "404", description = "Product not found", content = @Content())
    })
    public Mono<ServerResponse> getProduct(ServerRequest request) {
        Long id = Long.valueOf(request.pathVariable("id"));
        return catalogService.getProduct(id)
                .flatMap(product -> ServerResponse.ok().bodyValue(product))
                .onErrorResume(ShopException.class, e -> ServerResponse.notFound().build());
    }

    @Operation(operationId = "getAllCategories", summary = "List all categories", description = "Public category listing")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "List of categories"))
    public Mono<ServerResponse> getAllCategories(ServerRequest request) {
        return ServerResponse.ok().body(catalogService.getAllCategories(), CategoryResponse.class);
    }

    @Operation(operationId = "createProduct", summary = "Create a product", description = "Admin only")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Product created"),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN role", content = @Content())
    })
    public Mono<ServerResponse> createProduct(ServerRequest request) {
        return request.bodyToMono(CreateProductRequest.class)
                .flatMap(catalogService::createProduct)
                .flatMap(product -> ServerResponse.ok().bodyValue(product));
    }

    @Operation(operationId = "updateProduct", summary = "Update a product", description = "Admin only")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Product updated"),
            @ApiResponse(responseCode = "404", description = "Product not found", content = @Content()),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN role", content = @Content())
    })
    public Mono<ServerResponse> updateProduct(ServerRequest request) {
        Long id = Long.valueOf(request.pathVariable("id"));
        return request.bodyToMono(CreateProductRequest.class)
                .flatMap(req -> catalogService.updateProduct(id, req))
                .flatMap(product -> ServerResponse.ok().bodyValue(product))
                .onErrorResume(ShopException.class, e -> ServerResponse.notFound().build());
    }

    @Operation(operationId = "deleteProduct", summary = "Delete a product", description = "Admin only")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Product deleted", content = @Content()),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN role", content = @Content())
    })
    public Mono<ServerResponse> deleteProduct(ServerRequest request) {
        Long id = Long.valueOf(request.pathVariable("id"));
        return catalogService.deleteProduct(id)
                .then(ServerResponse.noContent().build());
    }

    @Operation(operationId = "createCategory", summary = "Create a category", description = "Admin only")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Category created"),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN role", content = @Content())
    })
    public Mono<ServerResponse> createCategory(ServerRequest request) {
        return request.bodyToMono(CreateCategoryRequest.class)
                .flatMap(catalogService::createCategory)
                .flatMap(category -> ServerResponse.ok().bodyValue(category));
    }

    @Operation(operationId = "updateCategory", summary = "Update a category", description = "Admin only")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Category updated"),
            @ApiResponse(responseCode = "404", description = "Category not found", content = @Content()),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN role", content = @Content())
    })
    public Mono<ServerResponse> updateCategory(ServerRequest request) {
        Long id = Long.valueOf(request.pathVariable("id"));
        return request.bodyToMono(CreateCategoryRequest.class)
                .flatMap(req -> catalogService.updateCategory(id, req))
                .flatMap(category -> ServerResponse.ok().bodyValue(category))
                .onErrorResume(ShopException.class, e -> ServerResponse.notFound().build());
    }

    @Operation(operationId = "deleteCategory", summary = "Delete a category", description = "Admin only")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Category deleted", content = @Content()),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN role", content = @Content())
    })
    public Mono<ServerResponse> deleteCategory(ServerRequest request) {
        Long id = Long.valueOf(request.pathVariable("id"));
        return catalogService.deleteCategory(id)
                .then(ServerResponse.noContent().build());
    }
}
