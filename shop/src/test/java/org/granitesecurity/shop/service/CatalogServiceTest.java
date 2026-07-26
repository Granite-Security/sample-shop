package org.granitesecurity.shop.service;

import org.granitesecurity.shop.domain.Category;
import org.granitesecurity.shop.domain.Product;
import org.granitesecurity.shop.dto.CategoryResponse;
import org.granitesecurity.shop.dto.CreateCategoryRequest;
import org.granitesecurity.shop.dto.CreateProductRequest;
import org.granitesecurity.shop.dto.MediaItem;
import org.granitesecurity.shop.dto.ProductResponse;
import org.granitesecurity.shop.repository.CategoryRepository;
import org.granitesecurity.shop.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private CatalogService catalogService;

    // ── Category tests ───────────────────────────────────────────────

    @Test
    void shouldReturnAllCategories() {
        var cat1 = new Category("Electronics", "Devices");
        cat1.setId(1L);
        var cat2 = new Category("Books", "Pages");
        cat2.setId(2L);

        when(categoryRepository.count()).thenReturn(Mono.just(2L));
        when(categoryRepository.findAllPaged(20, 0L)).thenReturn(Flux.just(cat1, cat2));

        StepVerifier.create(catalogService.getAllCategories(0, 20))
                .assertNext(result -> {
                    assert result.total() == 2;
                    assert result.items().size() == 2;
                    assert result.items().get(0).name().equals("Electronics");
                    assert result.items().get(1).name().equals("Books");
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnCategoryById() {
        var category = new Category("Gadgets", "Cool stuff");
        category.setId(10L);

        when(categoryRepository.findById(10L)).thenReturn(Mono.just(category));

        StepVerifier.create(catalogService.getCategory(10L))
                .assertNext(r -> {
                    assert r.id().equals(10L);
                    assert r.name().equals("Gadgets");
                    assert r.description().equals("Cool stuff");
                })
                .verifyComplete();
    }

    @Test
    void shouldErrorWhenCategoryNotFound() {
        when(categoryRepository.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(catalogService.getCategory(99L))
                .expectErrorMatches(e -> e instanceof ShopException
                        && e.getMessage().equals("Category not found: 99"))
                .verify();
    }

    @Test
    void shouldCreateCategory() {
        var request = new CreateCategoryRequest("Food", "Edibles");
        var saved = new Category("Food", "Edibles");
        saved.setId(5L);

        when(categoryRepository.save(any(Category.class))).thenReturn(Mono.just(saved));

        StepVerifier.create(catalogService.createCategory(request))
                .assertNext(r -> {
                    assert r.id().equals(5L);
                    assert r.name().equals("Food");
                    assert r.description().equals("Edibles");
                })
                .verifyComplete();
    }

    @Test
    void shouldUpdateCategory() {
        var existing = new Category("Old", "Before");
        existing.setId(3L);
        var request = new CreateCategoryRequest("New", "After");

        when(categoryRepository.findById(3L)).thenReturn(Mono.just(existing));
        when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(catalogService.updateCategory(3L, request))
                .assertNext(r -> {
                    assert r.id().equals(3L);
                    assert r.name().equals("New");
                    assert r.description().equals("After");
                })
                .verifyComplete();
    }

    @Test
    void shouldErrorWhenUpdatingNonexistentCategory() {
        when(categoryRepository.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(catalogService.updateCategory(99L, new CreateCategoryRequest("X", "Y")))
                .expectErrorMatches(e -> e instanceof ShopException
                        && e.getMessage().equals("Category not found: 99"))
                .verify();
    }

    @Test
    void shouldDeleteCategory() {
        when(categoryRepository.deleteById(1L)).thenReturn(Mono.empty());

        StepVerifier.create(catalogService.deleteCategory(1L))
                .verifyComplete();

        verify(categoryRepository).deleteById(1L);
    }

    // ── Product tests ────────────────────────────────────────────────

    @Test
    void shouldReturnAllProducts() {
        var product = new Product("A", BigDecimal.TEN, 5, 1L);
        product.setId(1L);
        product.setDescription("Desc");

        when(productRepository.count()).thenReturn(Mono.just(1L));
        when(productRepository.findAllPaged(20, 0L)).thenReturn(Flux.just(product));

        StepVerifier.create(catalogService.getAllProducts(0, 20))
                .assertNext(result -> {
                    assert result.total() == 1;
                    assert result.items().size() == 1;
                    var r = result.items().get(0);
                    assert r.id().equals(1L);
                    assert r.name().equals("A");
                    assert r.description().equals("Desc");
                    assert r.price().compareTo(BigDecimal.TEN) == 0;
                    assert r.stock() == 5;
                    assert r.categoryId().equals(1L);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnProductsByCategory() {
        var p1 = new Product("CatA", BigDecimal.valueOf(5), 1, 2L);
        p1.setId(10L);
        var p2 = new Product("CatB", BigDecimal.valueOf(8), 2, 2L);
        p2.setId(20L);

        when(productRepository.findByCategoryId(2L)).thenReturn(Flux.just(p1, p2));

        StepVerifier.create(catalogService.getProductsByCategory(2L))
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    void shouldReturnProductById() {
        var product = new Product("Widget", BigDecimal.valueOf(9.99), 10, 1L);
        product.setId(42L);

        when(productRepository.findById(42L)).thenReturn(Mono.just(product));

        StepVerifier.create(catalogService.getProduct(42L))
                .assertNext(r -> {
                    assert r.id().equals(42L);
                    assert r.name().equals("Widget");
                })
                .verifyComplete();
    }

    @Test
    void shouldErrorWhenProductNotFound() {
        when(productRepository.findById(77L)).thenReturn(Mono.empty());

        StepVerifier.create(catalogService.getProduct(77L))
                .expectErrorMatches(e -> e instanceof ShopException
                        && e.getMessage().equals("Product not found: 77"))
                .verify();
    }

    @Test
    void shouldCreateProduct() {
        var request = new CreateProductRequest(
                "NewItem", "New desc", BigDecimal.valueOf(15), 100, 1L, "img.jpg", null);
        var saved = new Product("NewItem", BigDecimal.valueOf(15), 100, 1L);
        saved.setId(7L);
        saved.setDescription("New desc");
        saved.setImageUrl("img.jpg");

        when(productRepository.save(any(Product.class))).thenReturn(Mono.just(saved));

        StepVerifier.create(catalogService.createProduct(request))
                .assertNext(r -> {
                    assert r.id().equals(7L);
                    assert r.name().equals("NewItem");
                    assert r.description().equals("New desc");
                    assert r.price().compareTo(BigDecimal.valueOf(15)) == 0;
                    assert r.stock() == 100;
                    assert r.categoryId().equals(1L);
                    assert r.imageUrl().equals("img.jpg");
                    assert r.media().isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void shouldCreateProductWithMedia() {
        var media = List.of(new MediaItem("products/abc/hero.jpg",
                "http://product-media.localhost:3902/products/abc/hero.jpg", "image/jpeg"));
        var request = new CreateProductRequest(
                "NewItem", "New desc", BigDecimal.valueOf(15), 100, 1L, "img.jpg", media);

        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product saved = invocation.getArgument(0);
            saved.setId(8L);
            return Mono.just(saved);
        });

        StepVerifier.create(catalogService.createProduct(request))
                .assertNext(r -> {
                    assert r.media().size() == 1;
                    assert r.media().get(0).key().equals("products/abc/hero.jpg");
                    assert r.media().get(0).contentType().equals("image/jpeg");
                })
                .verifyComplete();
    }

    @Test
    void shouldUpdateProduct() {
        var existing = new Product("Old", BigDecimal.ONE, 1, 1L);
        existing.setId(4L);
        var request = new CreateProductRequest(
                "Updated", "U desc", BigDecimal.valueOf(25), 50, 2L, "u.jpg", null);

        when(productRepository.findById(4L)).thenReturn(Mono.just(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(catalogService.updateProduct(4L, request))
                .assertNext(r -> {
                    assert r.id().equals(4L);
                    assert r.name().equals("Updated");
                    assert r.description().equals("U desc");
                    assert r.price().compareTo(BigDecimal.valueOf(25)) == 0;
                    assert r.stock() == 50;
                    assert r.categoryId().equals(2L);
                    assert r.imageUrl().equals("u.jpg");
                    assert r.media().isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void shouldErrorWhenUpdatingNonexistentProduct() {
        when(productRepository.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(catalogService.updateProduct(99L,
                        new CreateProductRequest("X", null, BigDecimal.ZERO, 0, 1L, null, null)))
                .expectErrorMatches(e -> e instanceof ShopException
                        && e.getMessage().equals("Product not found: 99"))
                .verify();
    }

    @Test
    void shouldDeleteProduct() {
        when(productRepository.deleteById(1L)).thenReturn(Mono.empty());

        StepVerifier.create(catalogService.deleteProduct(1L))
                .verifyComplete();

        verify(productRepository).deleteById(1L);
    }
}
