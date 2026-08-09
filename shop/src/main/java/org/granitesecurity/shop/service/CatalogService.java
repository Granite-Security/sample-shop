package org.granitesecurity.shop.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.granitesecurity.shop.domain.Category;
import org.granitesecurity.shop.domain.Product;
import org.granitesecurity.shop.dto.CategoryResponse;
import org.granitesecurity.shop.dto.CreateCategoryRequest;
import org.granitesecurity.shop.dto.CreateProductRequest;
import org.granitesecurity.shop.dto.MediaItem;
import org.granitesecurity.shop.dto.PagedResult;
import org.granitesecurity.shop.dto.ProductResponse;
import org.granitesecurity.shop.repository.CategoryRepository;
import org.granitesecurity.shop.repository.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class CatalogService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    public CatalogService(CategoryRepository categoryRepository, ProductRepository productRepository) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
    }

    public Mono<PagedResult<CategoryResponse>> getAllCategories(int page, int size) {
        long offset = (long) page * size;
        Mono<Long> count = categoryRepository.count();
        Flux<CategoryResponse> items = categoryRepository.findAllPaged(size, offset)
                .map(this::toCategoryResponse);
        return count.zipWith(items.collectList())
                .map(tuple -> new PagedResult<>(tuple.getT2(), tuple.getT1(), page, size));
    }

    public Mono<CategoryResponse> getCategory(Long id) {
        return categoryRepository.findById(id)
                .map(this::toCategoryResponse)
                .switchIfEmpty(Mono.error(
                        new ShopException("Category not found: " + id, HttpStatus.NOT_FOUND, "Not Found")));
    }

    public Mono<CategoryResponse> createCategory(CreateCategoryRequest request) {
        Category category = new Category(request.name(), request.description());
        Instant now = Instant.now();
        category.setCreatedAt(now);
        category.setUpdatedAt(now);
        return categoryRepository.save(category).map(this::toCategoryResponse);
    }

    public Mono<CategoryResponse> updateCategory(Long id, CreateCategoryRequest request) {
        return categoryRepository.findById(id)
                .switchIfEmpty(Mono.error(
                        new ShopException("Category not found: " + id, HttpStatus.NOT_FOUND, "Not Found")))
                .flatMap(existing -> {
                    existing.setName(request.name());
                    existing.setDescription(request.description());
                    existing.setUpdatedAt(Instant.now());
                    return categoryRepository.save(existing);
                })
                .map(this::toCategoryResponse);
    }

    public Mono<Void> deleteCategory(Long id) {
        return categoryRepository.deleteById(id);
    }

    public Mono<PagedResult<ProductResponse>> getAllProducts(int page, int size) {
        return getAllProducts(page, size, false);
    }

    // includeDiscontinued is for the admin views: a retired product is invisible
    // in the storefront, so without this there would be no way to find one and
    // put it back on sale.
    public Mono<PagedResult<ProductResponse>> getAllProducts(int page, int size, boolean includeDiscontinued) {
        long offset = (long) page * size;
        Mono<Long> count = includeDiscontinued
                ? productRepository.count()
                : productRepository.countActive();
        Flux<ProductResponse> items = (includeDiscontinued
                ? productRepository.findAllPagedIncludingDiscontinued(size, offset)
                : productRepository.findAllPaged(size, offset))
                .map(this::toProductResponse);
        return count.zipWith(items.collectList())
                .map(tuple -> new PagedResult<>(tuple.getT2(), tuple.getT1(), page, size));
    }

    public Flux<ProductResponse> getProductsByCategory(Long categoryId) {
        return productRepository.findByCategoryId(categoryId)
                .filter(product -> !Boolean.TRUE.equals(product.getDiscontinued()))
                .map(this::toProductResponse);
    }

    public Mono<ProductResponse> getProduct(Long id) {
        return productRepository.findById(id)
                .map(this::toProductResponse)
                .switchIfEmpty(Mono.error(
                        new ShopException("Product not found: " + id, HttpStatus.NOT_FOUND, "Not Found")));
    }

    public Mono<ProductResponse> createProduct(CreateProductRequest request) {
        Product product = new Product(request.name(), request.price(), request.stock(), request.categoryId());
        product.setDescription(request.description());
        product.setImageUrl(request.imageUrl());
        product.setMedia(serializeMedia(normalizeDefault(request.media())));
        product.setDiscontinued(Boolean.TRUE.equals(request.discontinued()));
        // created_at/updated_at are NOT NULL; R2DBC includes them in the INSERT,
        // bypassing the column defaults, so they must be set explicitly.
        Instant now = Instant.now();
        product.setCreatedAt(now);
        product.setUpdatedAt(now);
        return productRepository.save(product).map(this::toProductResponse);
    }

    public Mono<ProductResponse> updateProduct(Long id, CreateProductRequest request) {
        return productRepository.findById(id)
                .switchIfEmpty(Mono.error(
                        new ShopException("Product not found: " + id, HttpStatus.NOT_FOUND, "Not Found")))
                .flatMap(existing -> {
                    existing.setName(request.name());
                    existing.setDescription(request.description());
                    existing.setPrice(request.price());
                    existing.setStock(request.stock());
                    existing.setCategoryId(request.categoryId());
                    existing.setImageUrl(request.imageUrl());
                    existing.setMedia(serializeMedia(normalizeDefault(request.media())));
                    // null means "not stated" here, not false — see CreateProductRequest.
                    if (request.discontinued() != null) {
                        existing.setDiscontinued(request.discontinued());
                    }
                    existing.setUpdatedAt(Instant.now());
                    return productRepository.save(existing);
                })
                .map(this::toProductResponse);
    }

    // Soft delete. order_item references product with a NO ACTION foreign key,
    // so hard-deleting anything already ordered failed at the database and came
    // back as a 500; and it should fail, because order_item stores unit_price
    // but not the product name, so the row is the only record of what was
    // bought. Retiring a product hides it from the catalog and leaves order
    // history — and GET /api/shop/products/{id} — intact.
    public Mono<Void> deleteProduct(Long id) {
        return productRepository.findById(id)
                .switchIfEmpty(Mono.error(
                        new ShopException("Product not found: " + id, HttpStatus.NOT_FOUND, "Not Found")))
                .flatMap(existing -> productRepository.markDiscontinued(existing.getId()))
                .then();
    }

    private CategoryResponse toCategoryResponse(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getDescription());
    }

    private ProductResponse toProductResponse(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStock(),
                product.getCategoryId(),
                product.getImageUrl(),
                deserializeMedia(product.getMedia()),
                Boolean.TRUE.equals(product.getDiscontinued())
        );
    }

    // Guards the "at most one default" invariant server-side regardless of what
    // the client sends — keeps the first isDefault=true entry, clears the rest.
    private List<MediaItem> normalizeDefault(List<MediaItem> media) {
        if (media == null || media.isEmpty()) {
            return media;
        }
        boolean seenDefault = false;
        List<MediaItem> normalized = new ArrayList<>(media.size());
        for (MediaItem item : media) {
            if (item.isDefault() && seenDefault) {
                normalized.add(new MediaItem(item.key(), item.url(), item.contentType(), false));
            } else {
                if (item.isDefault()) {
                    seenDefault = true;
                }
                normalized.add(item);
            }
        }
        return normalized;
    }

    private String serializeMedia(List<MediaItem> media) {
        if (media == null || media.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(media);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize product media", e);
        }
    }

    private List<MediaItem> deserializeMedia(String media) {
        if (media == null || media.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(media, new TypeReference<List<MediaItem>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize product media", e);
        }
    }
}
