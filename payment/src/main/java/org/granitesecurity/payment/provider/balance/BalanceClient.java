package org.granitesecurity.payment.provider.balance;

import org.granitesecurity.payment.provider.PaymentProviderException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * payment → balance, over the internal scope. The HTTP shape of balance's intent
 * API, and nothing else — mapping to the provider vocabulary is
 * {@link BalanceProvider}'s job.
 */
@Component
public class BalanceClient {

    private final WebClient http;

    public BalanceClient(@Qualifier("balanceWebClient") WebClient balanceWebClient) {
        this.http = balanceWebClient;
    }

    public Mono<BalanceIntentView> createIntent(String username, long amountMinor, Long orderId) {
        return http.post()
                .uri("/api/balance/internal/intents")
                .bodyValue(Map.of(
                        "username", username == null ? "" : username,
                        "amountMinor", amountMinor,
                        "orderId", orderId == null ? 0L : orderId))
                .retrieve()
                .onStatus(HttpStatusCode::isError, BalanceClient::toProviderException)
                .bodyToMono(BalanceIntentView.class);
    }

    public Mono<BalanceIntentView> capture(String intentId) {
        return http.post()
                .uri("/api/balance/internal/intents/{id}/capture", intentId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, BalanceClient::toProviderException)
                .bodyToMono(BalanceIntentView.class);
    }

    public Mono<BalanceIntentView> get(String intentId) {
        return http.get()
                .uri("/api/balance/internal/intents/{id}", intentId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, BalanceClient::toProviderException)
                .bodyToMono(BalanceIntentView.class);
    }

    public Mono<BalanceIntentView> refund(String intentId) {
        return http.post()
                .uri("/api/balance/internal/intents/{id}/refund", intentId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, BalanceClient::toProviderException)
                .bodyToMono(BalanceIntentView.class);
    }

    /** Everything here is a provider failure from payment's point of view. */
    private static Mono<? extends Throwable> toProviderException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> new PaymentProviderException("balance",
                        "balance returned " + response.statusCode().value() + ": " + body, null));
    }

    /** Balance's intent, as it comes over the wire. */
    public record BalanceIntentView(
            String id,
            String username,
            long amountMinor,
            Long orderId,
            String status,
            String transferId,
            String declineReason) {
    }
}
