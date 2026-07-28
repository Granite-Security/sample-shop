package org.granitesecurity.notification.channel.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

// Moved verbatim from profile's notification package — timeout, single retry on
// 5xx/timeout, and the retryable predicate are unchanged.
@Component
public class ResendClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final String from;

    public ResendClient(WebClient.Builder webClientBuilder,
                        @Value("${resend.base-url}") String baseUrl,
                        @Value("${resend.api-key:}") String apiKey,
                        @Value("${resend.from}") String from) {
        this.from = from;
        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    Mono<String> send(String to, String subject, String html, String text) {
        return webClient.post()
                .uri("/emails")
                .bodyValue(Map.of(
                        "from", from,
                        "to", List.of(to),
                        "subject", subject,
                        "html", html,
                        "text", text))
                .retrieve()
                .bodyToMono(ResendEmailResponse.class)
                .timeout(TIMEOUT)
                .retryWhen(Retry.max(1).filter(ResendClient::isRetryable))
                .map(ResendEmailResponse::id);
    }

    private static boolean isRetryable(Throwable ex) {
        if (ex instanceof java.util.concurrent.TimeoutException) {
            return true;
        }
        if (ex instanceof org.springframework.web.reactive.function.client.WebClientResponseException wcre) {
            HttpStatusCode status = wcre.getStatusCode();
            return status.is5xxServerError();
        }
        return false;
    }
}
