package org.granitesecurity.shop;

import org.granitesecurity.shop.dto.CategoryResponse;
import org.granitesecurity.shop.dto.CreateCategoryRequest;
import org.granitesecurity.shop.dto.CreateProductRequest;
import org.granitesecurity.shop.dto.OrderItemResponse;
import org.granitesecurity.shop.dto.OrderResponse;
import org.granitesecurity.shop.dto.PagedResult;
import org.granitesecurity.shop.dto.PlaceOrderRequest;
import org.granitesecurity.shop.dto.ProductResponse;
import org.granitesecurity.shop.service.CatalogService;
import org.granitesecurity.shop.service.OrderService;
import org.granitesecurity.shop.service.ShopException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@SpringBootTest
class ShopRouteTest extends AbstractTestcontainers {

    @Autowired
    private ApplicationContext applicationContext;

    @MockitoBean
    private CatalogService catalogService;

    @MockitoBean
    private OrderService orderService;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .build();
    }

    // ── Public read endpoints (permitAll) ──────────────────────────

    @Test
    void productsListShouldBePublic() {
        var products = new PagedResult<>(List.of(
                new ProductResponse(1L, "Widget", "desc", BigDecimal.TEN, 5, 1L, null, null)
        ), 1L, 0, 20);
        when(catalogService.getAllProducts(0, 20)).thenReturn(Mono.just(products));

        webTestClient.get().uri("/api/shop/products")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].id").isEqualTo(1)
                .jsonPath("$.items[0].name").isEqualTo("Widget");
    }

    @Test
    void productByIdShouldBePublic() {
        when(catalogService.getProduct(1L)).thenReturn(Mono.just(
                new ProductResponse(1L, "Gadget", null, BigDecimal.valueOf(25), 10, 1L, null, null)
        ));

        webTestClient.get().uri("/api/shop/products/1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(1)
                .jsonPath("$.name").isEqualTo("Gadget")
                .jsonPath("$.price").isEqualTo(25);
    }

    @Test
    void productByIdShouldReturn404WhenMissing() {
        when(catalogService.getProduct(99L))
                .thenReturn(Mono.error(new ShopException("Product not found: 99", HttpStatus.NOT_FOUND, "Not Found")));

        webTestClient.get().uri("/api/shop/products/99")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void categoriesListShouldBePublic() {
        var categories = new PagedResult<>(List.of(
                new CategoryResponse(1L, "Electronics", "Devices")
        ), 1L, 0, 20);
        when(catalogService.getAllCategories(0, 20)).thenReturn(Mono.just(categories));

        webTestClient.get().uri("/api/shop/categories")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].id").isEqualTo(1)
                .jsonPath("$.items[0].name").isEqualTo("Electronics");
    }

    @Test
    void greetingsShouldBePublic() {
        webTestClient.get().uri("/api/shop/greetings")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("Hello, welcome to the shop!");
    }

    // ── Order endpoints — authentication rules ─────────────────────

    @Test
    void placeOrderShouldRejectAnonymous() {
        webTestClient.post().uri("/api/shop/orders")
                .bodyValue(Map.of("items", List.of(Map.of("productId", 1, "quantity", 1))))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void placeOrderShouldSucceedWithUserJwt() {
        when(orderService.placeOrder(anyString(), any(PlaceOrderRequest.class)))
                .thenReturn(Mono.just(new OrderResponse(
                        1L, "testuser", "PENDING", BigDecimal.valueOf(20), "CHF",
                        Instant.now(), List.of(new OrderItemResponse(1L, 1L, "Widget", 2, BigDecimal.TEN)),
                        null, null, null, null)));

        webTestClient
                .mutateWith(mockJwt().jwt(jwt -> jwt.subject("testuser")))
                .post().uri("/api/shop/orders")
                .bodyValue(Map.of("items", List.of(Map.of("productId", 1, "quantity", 2))))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("testuser")
                .jsonPath("$.status").isEqualTo("PENDING")
                .jsonPath("$.total").isEqualTo(20);
    }

    @Test
    void placeOrderShouldReturn400OnShopException() {
        when(orderService.placeOrder(anyString(), any(PlaceOrderRequest.class)))
                .thenReturn(Mono.error(new ShopException("Insufficient stock")));

        webTestClient
                .mutateWith(mockJwt().jwt(jwt -> jwt.subject("testuser")))
                .post().uri("/api/shop/orders")
                .bodyValue(Map.of("items", List.of(Map.of("productId", 1, "quantity", 999))))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void listOrdersShouldRejectAnonymous() {
        webTestClient.get().uri("/api/shop/orders")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void listOrdersShouldSucceedWithUserJwt() {
        var orders = new PagedResult<>(List.of(
                new OrderResponse(1L, "testuser", "PENDING", BigDecimal.TEN, "CHF", Instant.now(), List.of(), null, null, null, null)
        ), 1L, 0, 20);
        when(orderService.getOrdersForUser(anyString(), eq(0), eq(20))).thenReturn(Mono.just(orders));

        webTestClient
                .mutateWith(mockJwt().jwt(jwt -> jwt.subject("testuser")))
                .get().uri("/api/shop/orders")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].username").isEqualTo("testuser");
    }

    @Test
    void getOrderByIdShouldRejectAnonymous() {
        webTestClient.get().uri("/api/shop/orders/1")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void getOrderByIdShouldReturn200ForOwner() {
        when(orderService.getOrder(eq(1L), anyString(), anyBoolean()))
                .thenReturn(Mono.just(new OrderResponse(
                        1L, "owner", "PENDING", BigDecimal.valueOf(25), "CHF", Instant.now(), List.of(), null, null, null, null)));

        webTestClient
                .mutateWith(mockJwt().jwt(jwt -> jwt.subject("owner")))
                .get().uri("/api/shop/orders/1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("owner");
    }

    @Test
    void getOrderByIdShouldReturn404ForForeignUser() {
        when(orderService.getOrder(eq(1L), anyString(), anyBoolean()))
                .thenReturn(Mono.error(new ShopException("Order not found: 1", HttpStatus.NOT_FOUND, "Not Found")));

        webTestClient
                .mutateWith(mockJwt().jwt(jwt -> jwt.subject("otheruser")))
                .get().uri("/api/shop/orders/1")
                .exchange()
                .expectStatus().isNotFound();
    }

    // ── Admin endpoints — products ─────────────────────────────────

    @Test
    void createProductShouldRejectUser() {
        webTestClient
                .mutateWith(mockJwt().jwt(jwt -> jwt.subject("user").claim("roles", List.of("USER"))).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .post().uri("/api/shop/products")
                .bodyValue(Map.of("name", "X", "price", 10, "stock", 5, "categoryId", 1))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void createProductShouldSucceedForAdmin() {
        when(catalogService.createProduct(any(CreateProductRequest.class)))
                .thenReturn(Mono.just(new ProductResponse(
                        1L, "NewItem", "desc", BigDecimal.valueOf(15), 100, 1L, null, null)));

        webTestClient
                .mutateWith(mockJwt().jwt(jwt -> jwt.subject("admin").claim("roles", List.of("ADMIN"))).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .post().uri("/api/shop/products")
                .bodyValue(Map.of("name", "NewItem", "description", "desc",
                        "price", 15, "stock", 100, "categoryId", 1))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("NewItem");
    }

    @Test
    void updateProductShouldRejectUser() {
        webTestClient
                .mutateWith(mockJwt().jwt(jwt -> jwt.subject("user").claim("roles", List.of("USER"))).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .put().uri("/api/shop/products/1")
                .bodyValue(Map.of("name", "X", "price", 10, "stock", 5, "categoryId", 1))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void updateProductShouldSucceedForAdmin() {
        when(catalogService.updateProduct(eq(1L), any(CreateProductRequest.class)))
                .thenReturn(Mono.just(new ProductResponse(
                        1L, "Updated", null, BigDecimal.valueOf(20), 10, 1L, null, null)));

        webTestClient
                .mutateWith(mockJwt().jwt(jwt -> jwt.subject("admin").claim("roles", List.of("ADMIN"))).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .put().uri("/api/shop/products/1")
                .bodyValue(Map.of("name", "Updated", "price", 20, "stock", 10, "categoryId", 1))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("Updated")
                .jsonPath("$.price").isEqualTo(20);
    }

    @Test
    void deleteProductShouldRejectUser() {
        webTestClient
                .mutateWith(mockJwt().jwt(jwt -> jwt.subject("user").claim("roles", List.of("USER"))).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .delete().uri("/api/shop/products/1")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void deleteProductShouldSucceedForAdmin() {
        when(catalogService.deleteProduct(1L)).thenReturn(Mono.empty());

        webTestClient
                .mutateWith(mockJwt().jwt(jwt -> jwt.subject("admin").claim("roles", List.of("ADMIN"))).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .delete().uri("/api/shop/products/1")
                .exchange()
                .expectStatus().isNoContent();
    }

    // ── Admin endpoints — categories ───────────────────────────────

    @Test
    void createCategoryShouldRejectUser() {
        webTestClient
                .mutateWith(mockJwt().jwt(jwt -> jwt.subject("user").claim("roles", List.of("USER"))).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .post().uri("/api/shop/categories")
                .bodyValue(Map.of("name", "X"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void createCategoryShouldSucceedForAdmin() {
        when(catalogService.createCategory(any(CreateCategoryRequest.class)))
                .thenReturn(Mono.just(new CategoryResponse(1L, "NewCat", "desc")));

        webTestClient
                .mutateWith(mockJwt().jwt(jwt -> jwt.subject("admin").claim("roles", List.of("ADMIN"))).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .post().uri("/api/shop/categories")
                .bodyValue(Map.of("name", "NewCat", "description", "desc"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("NewCat");
    }

    @Test
    void updateCategoryShouldRejectUser() {
        webTestClient
                .mutateWith(mockJwt().jwt(jwt -> jwt.subject("user").claim("roles", List.of("USER"))).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .put().uri("/api/shop/categories/1")
                .bodyValue(Map.of("name", "X"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void updateCategoryShouldSucceedForAdmin() {
        when(catalogService.updateCategory(eq(1L), any(CreateCategoryRequest.class)))
                .thenReturn(Mono.just(new CategoryResponse(1L, "Updated", "new desc")));

        webTestClient
                .mutateWith(mockJwt().jwt(jwt -> jwt.subject("admin").claim("roles", List.of("ADMIN"))).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .put().uri("/api/shop/categories/1")
                .bodyValue(Map.of("name", "Updated", "description", "new desc"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("Updated");
    }

    @Test
    void deleteCategoryShouldRejectUser() {
        webTestClient
                .mutateWith(mockJwt().jwt(jwt -> jwt.subject("user").claim("roles", List.of("USER"))).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .delete().uri("/api/shop/categories/1")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void deleteCategoryShouldSucceedForAdmin() {
        when(catalogService.deleteCategory(1L)).thenReturn(Mono.empty());

        webTestClient
                .mutateWith(mockJwt().jwt(jwt -> jwt.subject("admin").claim("roles", List.of("ADMIN"))).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .delete().uri("/api/shop/categories/1")
                .exchange()
                .expectStatus().isNoContent();
    }
}
