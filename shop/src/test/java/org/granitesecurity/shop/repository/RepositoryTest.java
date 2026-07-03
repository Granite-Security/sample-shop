package org.granitesecurity.shop.repository;

import org.granitesecurity.shop.AbstractTestcontainers;
import org.granitesecurity.shop.domain.Category;
import org.granitesecurity.shop.domain.CustomerOrder;
import org.granitesecurity.shop.domain.OrderItem;
import org.granitesecurity.shop.domain.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.test.StepVerifier;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RepositoryTest extends AbstractTestcontainers {

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CustomerOrderRepository customerOrderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @BeforeEach
    void cleanUp() {
        orderItemRepository.deleteAll().block();
        customerOrderRepository.deleteAll().block();
        productRepository.deleteAll().block();
        categoryRepository.deleteAll().block();
    }

    @Test
    void shouldSaveAndFindCategory() {
        var category = new Category("Test Category", "A test category");

        StepVerifier.create(categoryRepository.save(category))
                .assertNext(saved -> {
                    assertThat(saved.getId()).isNotNull();
                    assertThat(saved.getName()).isEqualTo("Test Category");
                })
                .verifyComplete();
    }

    @Test
    void shouldFindCategoryById() {
        var saved = categoryRepository.save(new Category("Gadgets", "Cool gadgets")).block();

        StepVerifier.create(categoryRepository.findById(saved.getId()))
                .assertNext(found -> {
                    assertThat(found.getName()).isEqualTo("Gadgets");
                    assertThat(found.getDescription()).isEqualTo("Cool gadgets");
                })
                .verifyComplete();
    }

    @Test
    void shouldSaveAndFindProductByCategoryId() {
        var category = categoryRepository.save(new Category("Electronics", null)).block();

        var product = new Product("Widget", BigDecimal.valueOf(19.99), 10, category.getId());

        StepVerifier.create(productRepository.save(product))
                .assertNext(saved -> {
                    assertThat(saved.getId()).isNotNull();
                    assertThat(saved.getName()).isEqualTo("Widget");
                    assertThat(saved.getCategoryId()).isEqualTo(category.getId());
                })
                .verifyComplete();

        StepVerifier.create(productRepository.findByCategoryId(category.getId()))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void shouldMapSnakeCaseColumns() {
        var category = categoryRepository.save(new Category("Books", null)).block();
        var product = productRepository.save(new Product("Reactive Spring", BigDecimal.valueOf(44.99), 20, category.getId())).block();
        var order = customerOrderRepository.save(new CustomerOrder("buyer", "PENDING", BigDecimal.valueOf(89.98))).block();
        var item = new OrderItem(order.getId(), product.getId(), 2, BigDecimal.valueOf(44.99));

        StepVerifier.create(orderItemRepository.save(item))
                .assertNext(saved -> {
                    assertThat(saved.getId()).isNotNull();
                    assertThat(saved.getUnitPrice()).isEqualByComparingTo(BigDecimal.valueOf(44.99));
                })
                .verifyComplete();
    }

    @Test
    void shouldFindOrderItemsByOrderId() {
        var category = categoryRepository.save(new Category("Books", null)).block();
        var product = productRepository.save(new Product("Reactive Spring", BigDecimal.valueOf(44.99), 20, category.getId())).block();
        var order = customerOrderRepository.save(new CustomerOrder("buyer", "PENDING", BigDecimal.valueOf(89.98))).block();

        var item = new OrderItem(order.getId(), product.getId(), 2, BigDecimal.valueOf(44.99));

        StepVerifier.create(orderItemRepository.save(item))
                .assertNext(saved -> {
                    assertThat(saved.getId()).isNotNull();
                    assertThat(saved.getOrderId()).isEqualTo(order.getId());
                    assertThat(saved.getProductId()).isEqualTo(product.getId());
                })
                .verifyComplete();

        StepVerifier.create(orderItemRepository.findByOrderId(order.getId()))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void shouldFindOrdersByUsername() {
        var order = new CustomerOrder("testuser", "PENDING", BigDecimal.valueOf(49.99));

        StepVerifier.create(customerOrderRepository.save(order))
                .assertNext(saved -> {
                    assertThat(saved.getId()).isNotNull();
                    assertThat(saved.getUsername()).isEqualTo("testuser");
                })
                .verifyComplete();

        StepVerifier.create(customerOrderRepository.findByUsername("testuser"))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(customerOrderRepository.findByUsername("other"))
                .expectNextCount(0)
                .verifyComplete();
    }
}
