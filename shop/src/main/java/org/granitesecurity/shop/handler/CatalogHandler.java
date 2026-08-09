package org.granitesecurity.shop.handler;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.granitesecurity.shop.dto.CategoryResponse;
import org.granitesecurity.shop.dto.CreateCategoryRequest;
import org.granitesecurity.shop.dto.CreateProductRequest;
import org.granitesecurity.shop.dto.PagedResult;
import org.granitesecurity.shop.dto.ProductResponse;
import org.granitesecurity.shop.service.CatalogService;
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
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Paginated list of products"))
    public Mono<ServerResponse> getAllProducts(ServerRequest request) {
        int page = Integer.parseInt(request.queryParam("page").orElse("0"));
        int size = Integer.parseInt(request.queryParam("size").orElse("20"));
        boolean includeDiscontinued = request.queryParam("includeDiscontinued")
                .map(Boolean::parseBoolean).orElse(false);
        return catalogService.getAllProducts(page, size, includeDiscontinued)
                .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    @Operation(operationId = "getProduct", summary = "Get a product by ID", description = "Public product detail")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Product found"),
            @ApiResponse(responseCode = "404", description = "Product not found", content = @Content())
    })
    public Mono<ServerResponse> getProduct(ServerRequest request) {
        Long id = Long.valueOf(request.pathVariable("id"));
        return catalogService.getProduct(id)
                .flatMap(product -> ServerResponse.ok().bodyValue(product));
    }

    @Operation(operationId = "getAllCategories", summary = "List all categories", description = "Public category listing")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Paginated list of categories"))
    public Mono<ServerResponse> getAllCategories(ServerRequest request) {
        int page = Integer.parseInt(request.queryParam("page").orElse("0"));
        int size = Integer.parseInt(request.queryParam("size").orElse("20"));
        return catalogService.getAllCategories(page, size)
                .flatMap(result -> ServerResponse.ok().bodyValue(result));
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
                .flatMap(product -> ServerResponse.ok().bodyValue(product));
    }

    @Operation(operationId = "deleteProduct", summary = "Discontinue a product",
            description = "Admin only. Soft delete: the product leaves the catalog but is kept for order history.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Product discontinued", content = @Content()),
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
                .flatMap(category -> ServerResponse.ok().bodyValue(category));
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
