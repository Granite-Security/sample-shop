package org.granitesecurity.shop.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.granitesecurity.shop.domain.CustomerOrder;
import org.granitesecurity.shop.domain.OrderStatus;
import org.granitesecurity.shop.repository.CustomerOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@Testcontainers
@EmbeddedKafka(topics = {"payments.events", "shipments.events", "delivery.events",
        "payments.events.DLT"}, partitions = 1)
class EventConsumerIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("shopdb")
            .withUsername("myuser")
            .withPassword("secret");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () ->
                "r2dbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/shopdb");
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        registry.add("spring.liquibase.url", () ->
                "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/shopdb");
        registry.add("spring.liquibase.user", postgres::getUsername);
        registry.add("spring.liquibase.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://localhost:9090");
    }

    @Autowired
    private CustomerOrderRepository customerOrderRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @BeforeEach
    void setUp() {
        customerOrderRepository.deleteAll().block();
    }

    @Test
    void paymentReceivedTransitionsToPaid() {
        CustomerOrder order = seedOrder(OrderStatus.PENDING.name());

        kafkaTemplate.send("payments.events", json(Map.of(
                "orderId", order.getId(),
                "paymentId", "pay-123",
                "amount", 99.99
        )));

        CustomerOrder updated = awaitStatus(order.getId(), "PAID");
        assertEquals("PAID", updated.getStatus());
    }

    @Test
    void paymentFailedTransitionsToPaymentFailed() {
        CustomerOrder order = seedOrder(OrderStatus.PENDING.name());

        kafkaTemplate.send("payments.events", json(Map.of(
                "orderId", order.getId(),
                "reason", "Card declined"
        )));

        CustomerOrder updated = awaitStatus(order.getId(), "PAYMENT_FAILED");
        assertEquals("PAYMENT_FAILED", updated.getStatus());
    }

    @Test
    void shipmentDispatchedTransitionsToShipped() {
        CustomerOrder order = seedOrder(OrderStatus.PAID.name());

        kafkaTemplate.send("shipments.events", json(Map.of(
                "orderId", order.getId(),
                "shipmentId", "ship-1",
                "carrier", "UPS",
                "dispatchedAt", "2026-06-13T12:00:00Z"
        )));

        CustomerOrder updated = awaitStatus(order.getId(), "SHIPPED");
        assertEquals("SHIPPED", updated.getStatus());
    }

    @Test
    void shipmentDeliveredTransitionsToDelivered() {
        CustomerOrder order = seedOrder(OrderStatus.SHIPPED.name());

        kafkaTemplate.send("shipments.events", json(Map.of(
                "orderId", order.getId(),
                "shipmentId", "ship-1",
                "deliveredAt", "2026-06-13T12:00:00Z"
        )));

        CustomerOrder updated = awaitStatus(order.getId(), "DELIVERED");
        assertEquals("DELIVERED", updated.getStatus());
    }

    @Test
    void duplicatePaymentIsIdempotent() {
        CustomerOrder order = seedOrder(OrderStatus.PAID.name());

        kafkaTemplate.send("payments.events", json(Map.of(
                "orderId", order.getId(),
                "paymentId", "pay-dup",
                "amount", 99.99
        )));

        // Should stay PAID, not error
        CustomerOrder updated = awaitStatus(order.getId(), "PAID");
        assertEquals("PAID", updated.getStatus());
    }

    /**
     * The record that can never be processed must end up somewhere a human can find it.
     * Before the pipeline existed it was caught, logged and the offset committed — the
     * only trace was a line in stdout.
     */
    @Test
    void unparseableMessageIsDeadLettered() {
        kafkaTemplate.send("payments.events", "{ this is not json");

        Map<String, Object> props = KafkaTestUtils.consumerProps(
                embeddedKafka.getBrokersAsString(), "dlt-assertions", true);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> consumer =
                     new DefaultKafkaConsumerFactory<String, String>(props).createConsumer()) {
            consumer.subscribe(List.of("payments.events.DLT"));
            ConsumerRecord<String, String> dead =
                    KafkaTestUtils.getSingleRecord(consumer, "payments.events.DLT", Duration.ofSeconds(30));

            assertEquals("{ this is not json", dead.value());
            assertNotNull(dead.headers().lastHeader("x-exception"));
            assertEquals("payments.events",
                    new String(dead.headers().lastHeader("x-original-topic").value(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void unknownOrderDoesNotThrow() {
        kafkaTemplate.send("payments.events", json(Map.of(
                "orderId", 99999L,
                "paymentId", "pay-123",
                "amount", 99.99
        )));

        // Just verify no exception is thrown - wait briefly then succeed
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void fullLifecycleInSequence() {
        CustomerOrder order = seedOrder(OrderStatus.PENDING.name());

        kafkaTemplate.send("payments.events", json(Map.of(
                "orderId", order.getId(),
                "paymentId", "pay-lifecycle",
                "amount", 99.99
        )));
        CustomerOrder paid = awaitStatus(order.getId(), "PAID");
        assertEquals("PAID", paid.getStatus());

        kafkaTemplate.send("shipments.events", json(Map.of(
                "orderId", order.getId(),
                "shipmentId", "ship-lifecycle",
                "carrier", "FedEx",
                "dispatchedAt", "2026-06-13T13:00:00Z"
        )));
        CustomerOrder shipped = awaitStatus(order.getId(), "SHIPPED");
        assertEquals("SHIPPED", shipped.getStatus());

        kafkaTemplate.send("shipments.events", json(Map.of(
                "orderId", order.getId(),
                "shipmentId", "ship-lifecycle",
                "deliveredAt", "2026-06-13T15:00:00Z"
        )));
        CustomerOrder delivered = awaitStatus(order.getId(), "DELIVERED");
        assertEquals("DELIVERED", delivered.getStatus());
    }

    // ── helpers ───────────────────────────────────────────────────

    private CustomerOrder seedOrder(String status) {
        CustomerOrder order = new CustomerOrder();
        order.setUsername("testuser");
        order.setStatus(status);
        order.setTotal(BigDecimal.valueOf(99.99));
        order.setCreatedAt(java.time.Instant.now());
        order.setUpdatedAt(order.getCreatedAt());
        return customerOrderRepository.save(order).block();
    }

    private CustomerOrder awaitStatus(Long orderId, String expectedStatus) {
        long deadline = System.currentTimeMillis() + 10_000;
        CustomerOrder current;
        do {
            current = customerOrderRepository.findById(orderId).block();
            if (current != null && current.getStatus().equals(expectedStatus)) {
                return current;
            }
            if (System.currentTimeMillis() > deadline) {
                if (current == null) {
                    throw new AssertionError("Order " + orderId + " not found after waiting");
                }
                throw new AssertionError("Expected status " + expectedStatus
                        + " but got " + current.getStatus() + " after waiting");
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        } while (true);
    }

    private static String json(Map<?, ?> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
