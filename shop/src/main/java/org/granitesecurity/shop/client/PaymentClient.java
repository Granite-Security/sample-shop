package org.granitesecurity.shop.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Map;

@Service
public class PaymentClient {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PaymentClient.class);

    private final WebClient paymentWebClient;

    public PaymentClient(@Qualifier("paymentWebClient") WebClient paymentWebClient) {
        this.paymentWebClient = paymentWebClient;
    }

    /**
     * Payment status keyed by order id. Orders with no payment row are absent.
     * A failure here must NOT be swallowed into an empty map — an empty map
     * reads as "nothing was ever paid", which would turn a payment outage into
     * permission to delete a paying customer's orders.
     */
    public Mono<Map<Long, String>> statusesByOrderIds(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        return paymentWebClient.post()
                .uri("/api/payments/internal/statuses")
                .bodyValue(Map.of("orderIds", orderIds))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapError)
                .bodyToMono(new ParameterizedTypeReference<Map<Long, String>>() {});
    }

    private Mono<? extends Throwable> mapError(ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> {
                    log.warn("payment request failed: status={} body={}", status, body);
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "payment request failed"));
                });
    }
}
