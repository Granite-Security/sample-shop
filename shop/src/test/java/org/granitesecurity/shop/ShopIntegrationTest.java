package org.granitesecurity.shop;

import org.granitesecurity.shop.dto.OrderResponse;
import org.granitesecurity.shop.dto.PagedResult;
import org.granitesecurity.shop.dto.ProductResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShopIntegrationTest extends AbstractTestcontainers {

    @Autowired
    private ApplicationContext applicationContext;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void shouldBrowseCatalogAndPlaceOrder() {
        ProductResponse product = fetchFirstProduct();
        int initialStock = product.stock();

        OrderResponse order = placeOrder(product.id(), 2);

        verifyStockDecremented(product.id(), initialStock, 2);

        verifyOrderRetrievable(order.id());
    }

    private ProductResponse fetchFirstProduct() {
        PagedResult<ProductResponse> result = webTestClient
                .get().uri("/api/shop/products")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new org.springframework.core.ParameterizedTypeReference<PagedResult<ProductResponse>>() {})
                .returnResult()
                .getResponseBody();

        assert result != null && !result.items().isEmpty() : "Seed products should exist";
        return result.items().get(0);
    }

    private OrderResponse placeOrder(Long productId, int quantity) {
        var request = Map.of("items", List.of(Map.of("productId", productId, "quantity", quantity)));

        return webTestClient
                .mutateWith(mockJwt().jwt(jwt -> jwt.subject("testuser")))
                .post().uri("/api/shop/orders")
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrderResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private void verifyStockDecremented(Long productId, int initialStock, int quantityOrdered) {
        ProductResponse updated = webTestClient
                .get().uri("/api/shop/products/{id}", productId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductResponse.class)
                .returnResult()
                .getResponseBody();

        assert updated != null : "Product should exist after order";
        assert updated.stock() == initialStock - quantityOrdered :
                "Expected stock " + (initialStock - quantityOrdered) + " but got " + updated.stock();
    }

    private void verifyOrderRetrievable(Long orderId) {
        OrderResponse fetched = webTestClient
                .mutateWith(mockJwt().jwt(jwt -> jwt.subject("testuser")))
                .get().uri("/api/shop/orders/{id}", orderId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrderResponse.class)
                .returnResult()
                .getResponseBody();

        assert fetched != null : "Order should be retrievable";
        assert fetched.id().equals(orderId) : "Order ID should match";
        assert fetched.username().equals("testuser") : "Order should belong to testuser";
        assert fetched.status().equals("PENDING") : "Order should be PENDING";
        assert fetched.total().compareTo(BigDecimal.ZERO) > 0 : "Order total should be positive";
        assert !fetched.items().isEmpty() : "Order should have items";

        PagedResult<OrderResponse> userOrdersPage = webTestClient
                .mutateWith(mockJwt().jwt(jwt -> jwt.subject("testuser")))
                .get().uri("/api/shop/orders")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new org.springframework.core.ParameterizedTypeReference<PagedResult<OrderResponse>>() {})
                .returnResult()
                .getResponseBody();

        assert userOrdersPage != null : "User orders list should not be null";
        assert userOrdersPage.items().stream().anyMatch(o -> o.id().equals(orderId)) : "Order should be in user's order list";
    }

    @Test
    void shouldRejectUnauthenticatedOrderPlacement() {
        var request = Map.of("items", List.of(Map.of("productId", 1L, "quantity", 1)));

        webTestClient
                .post().uri("/api/shop/orders")
                .bodyValue(request)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void shouldRejectOrderForWrongUser() {
        ProductResponse product = fetchFirstProduct();
        var request = Map.of("items", List.of(Map.of("productId", product.id(), "quantity", 1)));

        OrderResponse order = webTestClient
                .mutateWith(mockJwt().jwt(jwt -> jwt.subject("owner")))
                .post().uri("/api/shop/orders")
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrderResponse.class)
                .returnResult()
                .getResponseBody();

        assert order != null;

        webTestClient
                .mutateWith(mockJwt().jwt(jwt -> jwt.subject("otheruser")))
                .get().uri("/api/shop/orders/{id}", order.id())
                .exchange()
                .expectStatus().isNotFound();
    }
}
