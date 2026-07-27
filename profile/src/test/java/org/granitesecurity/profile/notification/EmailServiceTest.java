package org.granitesecurity.profile.notification;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

class EmailServiceTest {

    private static final String BASE_URL = "https://api.resend.com";
    private static final String FROM = "Granite Security <no-reply@notify.granite-security.org>";

    @Test
    void disabledWhenApiKeyBlank() {
        ResendClient resendClient = new ResendClient(WebClient.builder(), BASE_URL, "", FROM);
        EmailService emailService = new EmailService(resendClient, "");

        StepVerifier.create(emailService.sendPasswordChanged("alice@example.com", "Alice", Instant.now()))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void returnsTrueOn200() {
        AtomicInteger calls = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body("{\"id\":\"msg_123\"}")
                    .build());
        });
        ResendClient resendClient = new ResendClient(builder, BASE_URL, "re_test", FROM);
        EmailService emailService = new EmailService(resendClient, "re_test");

        StepVerifier.create(emailService.sendPasswordChanged("alice@example.com", "Alice", Instant.now()))
                .expectNext(true)
                .verifyComplete();

        assert calls.get() == 1;
    }

    @Test
    void returnsFalseOn422WithoutRetry() {
        AtomicInteger calls = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.UNPROCESSABLE_ENTITY)
                    .header("Content-Type", "application/json")
                    .body("{\"message\":\"invalid\"}")
                    .build());
        });
        ResendClient resendClient = new ResendClient(builder, BASE_URL, "re_test", FROM);
        EmailService emailService = new EmailService(resendClient, "re_test");

        StepVerifier.create(emailService.sendPasswordChanged("alice@example.com", "Alice", Instant.now()))
                .expectNext(false)
                .verifyComplete();

        assert calls.get() == 1;
    }

    @Test
    void retriesOnceOn500() {
        AtomicInteger calls = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                        .header("Content-Type", "application/json")
                        .body("{\"message\":\"boom\"}")
                        .build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body("{\"id\":\"msg_456\"}")
                    .build());
        });
        ResendClient resendClient = new ResendClient(builder, BASE_URL, "re_test", FROM);
        EmailService emailService = new EmailService(resendClient, "re_test");

        StepVerifier.create(emailService.sendPasswordChanged("alice@example.com", "Alice", Instant.now()))
                .expectNext(true)
                .verifyComplete();

        assert calls.get() == 2;
    }
}
