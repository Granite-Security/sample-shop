package org.granitesecurity.authserver;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Singleton container, started once for the whole JVM and never stopped —
 * deliberately not {@code @Testcontainers} + {@code @Container}, which start and
 * stop the container around <em>each</em> test class. Spring caches one
 * application context across every subclass of this base, so the second class to
 * run would keep a datasource pointing at the first class's now-stopped
 * container and fail with connection-refused. Ryuk reaps the container when the
 * JVM exits.
 */
public abstract class AbstractTestcontainers {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("authdb")
            .withUsername("myuser")
            .withPassword("secret");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
