package org.granitesecurity.profile.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;

/** profile → payment, read-only, for the orphan sweep. */
@Service
public class PaymentAdminClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentAdminClient.class);

    private final WebClient paymentWebClient;

    public PaymentAdminClient(@Qualifier("paymentWebClient") WebClient paymentWebClient) {
        this.paymentWebClient = paymentWebClient;
    }

    public Mono<List<Long>> orderIds() {
        return paymentWebClient.get()
                .uri("/api/payments/internal/order-ids")
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapError)
                .bodyToMono(new ParameterizedTypeReference<List<Long>>() {});
    }

    private Mono<? extends Throwable> mapError(ClientResponse response) {
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> {
                    log.warn("payment request failed: status={} body={}", response.statusCode(), body);
                    return Mono.error(new ResponseStatusException(
                            HttpStatus.BAD_GATEWAY, "payment request failed"));
                });
    }
}
