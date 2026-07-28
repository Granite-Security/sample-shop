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

/** profile → delivery, read-only, for the orphan sweep. */
@Service
public class DeliveryAdminClient {

    private static final Logger log = LoggerFactory.getLogger(DeliveryAdminClient.class);

    private final WebClient deliveryWebClient;

    public DeliveryAdminClient(@Qualifier("deliveryWebClient") WebClient deliveryWebClient) {
        this.deliveryWebClient = deliveryWebClient;
    }

    public Mono<List<Long>> orderIds() {
        return deliveryWebClient.get()
                .uri("/api/delivery/internal/order-ids")
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapError)
                .bodyToMono(new ParameterizedTypeReference<List<Long>>() {});
    }

    private Mono<? extends Throwable> mapError(ClientResponse response) {
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> {
                    log.warn("delivery request failed: status={} body={}", response.statusCode(), body);
                    return Mono.error(new ResponseStatusException(
                            HttpStatus.BAD_GATEWAY, "delivery request failed"));
                });
    }
}
