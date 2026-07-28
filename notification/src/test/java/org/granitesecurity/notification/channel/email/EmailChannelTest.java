package org.granitesecurity.notification.channel.email;

import org.granitesecurity.notification.channel.Channel;
import org.granitesecurity.notification.channel.DeliveryResult;
import org.granitesecurity.notification.channel.RenderedMessage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ported from profile's EmailServiceTest. The cases are unchanged — disabled when the
 * key is blank, 200 succeeds, 4xx does not retry, 5xx retries once — but they now run
 * against EmailChannel, since the send path moved behind the NotificationChannel
 * interface when the templates were split out.
 */
class EmailChannelTest {

    private static final String BASE_URL = "https://api.resend.com";
    private static final String FROM = "Granite Security <no-reply@notify.granite-security.org>";

    private static RenderedMessage message() {
        return new RenderedMessage(Channel.EMAIL, "alice@example.com", "Your password was changed",
                "<p>Hi Alice,</p>", "Hi Alice,");
    }

    private static EmailChannel channelWith(WebClient.Builder builder, String apiKey) {
        return new EmailChannel(new ResendClient(builder, BASE_URL, apiKey, FROM), apiKey);
    }

    private static WebClient.Builder respondingWith(AtomicInteger calls, HttpStatus status, String body) {
        return WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(status)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .build());
        });
    }

    @Test
    void disabledWhenApiKeyBlank() {
        EmailChannel channel = channelWith(WebClient.builder(), "");

        StepVerifier.create(channel.send(message()))
                .expectNextMatches(r -> r.status() == DeliveryResult.Status.SKIPPED_DISABLED)
                .verifyComplete();
    }

    @Test
    void sentOn200() {
        AtomicInteger calls = new AtomicInteger();
        EmailChannel channel = channelWith(respondingWith(calls, HttpStatus.OK, "{\"id\":\"msg_123\"}"), "re_test");

        StepVerifier.create(channel.send(message()))
                .expectNextMatches(r -> r.status() == DeliveryResult.Status.SENT
                        && "msg_123".equals(r.providerMessageId()))
                .verifyComplete();

        assertEquals(1, calls.get());
    }

    @Test
    void failsOn422WithoutRetry() {
        AtomicInteger calls = new AtomicInteger();
        EmailChannel channel = channelWith(
                respondingWith(calls, HttpStatus.UNPROCESSABLE_ENTITY, "{\"message\":\"invalid\"}"), "re_test");

        StepVerifier.create(channel.send(message()))
                .expectNextMatches(r -> r.status() == DeliveryResult.Status.FAILED)
                .verifyComplete();

        assertEquals(1, calls.get());
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
        EmailChannel channel = channelWith(builder, "re_test");

        StepVerifier.create(channel.send(message()))
                .expectNextMatches(r -> r.status() == DeliveryResult.Status.SENT)
                .verifyComplete();

        assertEquals(2, calls.get());
    }

    @Test
    void failsWithoutRecipient() {
        AtomicInteger calls = new AtomicInteger();
        EmailChannel channel = channelWith(respondingWith(calls, HttpStatus.OK, "{\"id\":\"x\"}"), "re_test");

        RenderedMessage noRecipient = new RenderedMessage(Channel.EMAIL, "", "s", "<p>h</p>", "t");

        StepVerifier.create(channel.send(noRecipient))
                .expectNextMatches(r -> r.status() == DeliveryResult.Status.FAILED)
                .verifyComplete();

        assertEquals(0, calls.get());
    }
}
