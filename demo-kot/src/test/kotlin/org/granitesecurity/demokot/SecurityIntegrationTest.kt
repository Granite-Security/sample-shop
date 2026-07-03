package org.granitesecurity.demokot

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.context.ApplicationContext
import org.junit.jupiter.api.BeforeEach
import reactor.test.StepVerifier
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity

@SpringBootTest
class SecurityIntegrationTest {

    @Autowired
    lateinit var context: ApplicationContext

    lateinit var webTestClient: WebTestClient

    @BeforeEach
    fun setup() {
        webTestClient = WebTestClient.bindToApplicationContext(context)
            .apply(springSecurity())
            .build()
    }

    @Test
    fun `hello endpoint should be available to everyone`() {
        webTestClient.get()
            .uri("/api/hello")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).isEqualTo("Hello, World!")
    }

    @Test
    fun `heartbeat endpoint should be protected`() {
        webTestClient.get()
            .uri("/api/heartbeat")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    @WithMockUser
    fun `heartbeat endpoint should be available to authenticated users`() {
        webTestClient.get()
            .uri("/api/heartbeat")
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith(org.springframework.http.MediaType.TEXT_EVENT_STREAM)
            .returnResult(String::class.java)
            .responseBody
            .take(1)
            .let { flux ->
                StepVerifier.create(flux)
                    .expectNext("heartbeat")
                    .thenCancel()
                    .verify(java.time.Duration.ofSeconds(5))
            }
    }
}
