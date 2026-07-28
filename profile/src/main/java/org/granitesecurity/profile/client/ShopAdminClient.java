package org.granitesecurity.profile.client;

import org.granitesecurity.profile.dto.PurgeEligibility;
import org.granitesecurity.profile.dto.PurgeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** profile → shop, over the shared internal scope. */
@Service
public class ShopAdminClient {

    private static final Logger log = LoggerFactory.getLogger(ShopAdminClient.class);

    private final WebClient shopWebClient;

    public ShopAdminClient(@Qualifier("shopWebClient") WebClient shopWebClient) {
        this.shopWebClient = shopWebClient;
    }

    public Mono<PurgeEligibility> purgeEligibility(String username) {
        return shopWebClient.get()
                .uri("/api/shop/internal/users/{username}/purge-eligibility", username)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapError)
                .bodyToMono(PurgeEligibility.class);
    }

    public Mono<PurgeResult> purgeOrders(String username) {
        return shopWebClient.method(HttpMethod.DELETE)
                .uri("/api/shop/internal/users/{username}/orders", username)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapError)
                .bodyToMono(PurgeResult.class);
    }

    // ── Orphan sweep (§8 Phase 6), read-only ────────────────────────

    public Mono<java.util.List<OrderOwner>> orderOwners() {
        return shopWebClient.get()
                .uri("/api/shop/internal/orders/owners")
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapError)
                .bodyToFlux(OrderOwner.class)
                .collectList();
    }

    /** Of these order ids, the ones shop no longer has. */
    public Mono<java.util.List<Long>> unknownOrderIds(java.util.List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Mono.just(java.util.List.of());
        }
        return shopWebClient.post()
                .uri("/api/shop/internal/orders/unknown")
                .bodyValue(java.util.Map.of("orderIds", orderIds))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapError)
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<java.util.List<Long>>() {});
    }

    public record OrderOwner(String username, long orderCount) {}

    private Mono<? extends Throwable> mapError(ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> {
                    log.warn("shop request failed: status={} body={}", status, body);
                    // shop re-checks eligibility itself and 409s if the user
                    // gained a paid order since our check — surface that rather
                    // than reporting a generic upstream failure.
                    if (status.value() == HttpStatus.CONFLICT.value()) {
                        return Mono.error(new ResponseStatusException(status, body));
                    }
                    return Mono.error(new ResponseStatusException(
                            HttpStatus.BAD_GATEWAY, "shop request failed"));
                });
    }
}
