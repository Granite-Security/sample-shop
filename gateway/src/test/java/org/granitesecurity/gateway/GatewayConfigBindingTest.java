package org.granitesecurity.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.HttpClientProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against config that binds to nothing.
 *
 * <p>Spring Cloud Gateway 4.2 moved these keys under {@code server.webflux} and 5.x
 * dropped the old {@code spring.cloud.gateway.*} forms outright — they are absent from
 * the configuration metadata even as deprecated aliases. Misplaced keys therefore fail
 * silently: the application starts, the YAML looks right, and the settings do nothing.
 *
 * <p>That is not hypothetical here. The connection-pool settings sat under the old
 * prefix and were inert, which let the gateway keep pooled connections to pods that had
 * been replaced and serve 500s after every deploy. The YAML even carried a comment
 * concluding the settings "did not work" — they were never applied.
 *
 * <p>Asserting on the bound objects rather than the YAML text is the point: only the
 * bound value proves the key was recognised.
 */
@SpringBootTest
class GatewayConfigBindingTest {

    @Autowired
    private HttpClientProperties httpClient;

    @Test
    void connectionPoolSettingsAreActuallyBound() {
        assertThat(httpClient.getPool().getMaxIdleTime())
                .as("max-idle-time must bind, or dead connections outlive the pods behind them")
                .isEqualTo(Duration.ofSeconds(15));

        assertThat(httpClient.getPool().getMaxLifeTime())
                .as("max-life-time bounds a busy connection, which idle eviction never reaches")
                .isEqualTo(Duration.ofSeconds(60));

        assertThat(httpClient.getPool().getEvictionInterval())
                .as("background eviction, so idle connections are closed before they are reused")
                .isEqualTo(Duration.ofSeconds(15));
    }

}
