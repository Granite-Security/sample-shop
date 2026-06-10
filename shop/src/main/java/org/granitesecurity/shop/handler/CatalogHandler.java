package org.granitesecurity.shop.handler;

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

    public Mono<ServerResponse> getAllProducts(ServerRequest request) {
        return ServerResponse.ok().body(catalogService.getAllProducts(), ProductResponse.class);
    }

    public Mono<ServerResponse> getProduct(ServerRequest request) {
        Long id = Long.valueOf(request.pathVariable("id"));
        return catalogService.getProduct(id)
                .flatMap(product -> ServerResponse.ok().bodyValue(product))
                .onErrorResume(ShopException.class, e -> ServerResponse.notFound().build());
    }

    public Mono<ServerResponse> getAllCategories(ServerRequest request) {
        return ServerResponse.ok().body(catalogService.getAllCategories(), CategoryResponse.class);
    }

    public Mono<ServerResponse> createProduct(ServerRequest request) {
        return request.bodyToMono(CreateProductRequest.class)
                .flatMap(catalogService::createProduct)
                .flatMap(product -> ServerResponse.ok().bodyValue(product));
    }

    public Mono<ServerResponse> updateProduct(ServerRequest request) {
        Long id = Long.valueOf(request.pathVariable("id"));
        return request.bodyToMono(CreateProductRequest.class)
                .flatMap(req -> catalogService.updateProduct(id, req))
                .flatMap(product -> ServerResponse.ok().bodyValue(product))
                .onErrorResume(ShopException.class, e -> ServerResponse.notFound().build());
    }

    public Mono<ServerResponse> deleteProduct(ServerRequest request) {
        Long id = Long.valueOf(request.pathVariable("id"));
        return catalogService.deleteProduct(id)
                .then(ServerResponse.noContent().build());
    }

    public Mono<ServerResponse> createCategory(ServerRequest request) {
        return request.bodyToMono(CreateCategoryRequest.class)
                .flatMap(catalogService::createCategory)
                .flatMap(category -> ServerResponse.ok().bodyValue(category));
    }

    public Mono<ServerResponse> updateCategory(ServerRequest request) {
        Long id = Long.valueOf(request.pathVariable("id"));
        return request.bodyToMono(CreateCategoryRequest.class)
                .flatMap(req -> catalogService.updateCategory(id, req))
                .flatMap(category -> ServerResponse.ok().bodyValue(category))
                .onErrorResume(ShopException.class, e -> ServerResponse.notFound().build());
    }

    public Mono<ServerResponse> deleteCategory(ServerRequest request) {
        Long id = Long.valueOf(request.pathVariable("id"));
        return catalogService.deleteCategory(id)
                .then(ServerResponse.noContent().build());
    }
}
