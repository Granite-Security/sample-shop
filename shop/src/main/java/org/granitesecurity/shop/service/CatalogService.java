package org.granitesecurity.shop.service;

import org.granitesecurity.shop.domain.Category;
import org.granitesecurity.shop.domain.Product;
import org.granitesecurity.shop.dto.CategoryResponse;
import org.granitesecurity.shop.dto.CreateCategoryRequest;
import org.granitesecurity.shop.dto.CreateProductRequest;
import org.granitesecurity.shop.dto.ProductResponse;
import org.granitesecurity.shop.repository.CategoryRepository;
import org.granitesecurity.shop.repository.ProductRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class CatalogService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    public CatalogService(CategoryRepository categoryRepository, ProductRepository productRepository) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
    }

    public Flux<CategoryResponse> getAllCategories() {
        return categoryRepository.findAll().map(this::toCategoryResponse);
    }

    public Mono<CategoryResponse> getCategory(Long id) {
        return categoryRepository.findById(id)
                .map(this::toCategoryResponse)
                .switchIfEmpty(Mono.error(new ShopException("Category not found: " + id)));
    }

    public Mono<CategoryResponse> createCategory(CreateCategoryRequest request) {
        Category category = new Category(request.name(), request.description());
        return categoryRepository.save(category).map(this::toCategoryResponse);
    }

    public Mono<CategoryResponse> updateCategory(Long id, CreateCategoryRequest request) {
        return categoryRepository.findById(id)
                .switchIfEmpty(Mono.error(new ShopException("Category not found: " + id)))
                .flatMap(existing -> {
                    existing.setName(request.name());
                    existing.setDescription(request.description());
                    return categoryRepository.save(existing);
                })
                .map(this::toCategoryResponse);
    }

    public Mono<Void> deleteCategory(Long id) {
        return categoryRepository.deleteById(id);
    }

    public Flux<ProductResponse> getAllProducts() {
        return productRepository.findAll().map(this::toProductResponse);
    }

    public Flux<ProductResponse> getProductsByCategory(Long categoryId) {
        return productRepository.findByCategoryId(categoryId).map(this::toProductResponse);
    }

    public Mono<ProductResponse> getProduct(Long id) {
        return productRepository.findById(id)
                .map(this::toProductResponse)
                .switchIfEmpty(Mono.error(new ShopException("Product not found: " + id)));
    }

    public Mono<ProductResponse> createProduct(CreateProductRequest request) {
        Product product = new Product(request.name(), request.price(), request.stock(), request.categoryId());
        product.setDescription(request.description());
        product.setImageUrl(request.imageUrl());
        return productRepository.save(product).map(this::toProductResponse);
    }

    public Mono<ProductResponse> updateProduct(Long id, CreateProductRequest request) {
        return productRepository.findById(id)
                .switchIfEmpty(Mono.error(new ShopException("Product not found: " + id)))
                .flatMap(existing -> {
                    existing.setName(request.name());
                    existing.setDescription(request.description());
                    existing.setPrice(request.price());
                    existing.setStock(request.stock());
                    existing.setCategoryId(request.categoryId());
                    existing.setImageUrl(request.imageUrl());
                    return productRepository.save(existing);
                })
                .map(this::toProductResponse);
    }

    public Mono<Void> deleteProduct(Long id) {
        return productRepository.deleteById(id);
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
                product.getImageUrl()
        );
    }
}
