package org.granitesecurity.profile.client;

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

import java.util.Map;

// Only delete() remains here — presign now goes straight from the browser to
// storage (see StorageSec/StorageService), so profile no longer brokers the
// upload leg, only the ownership-checked delete leg.
@Service
public class StorageClient {

    private static final Logger log = LoggerFactory.getLogger(StorageClient.class);

    private final WebClient storageWebClient;

    public StorageClient(@Qualifier("storageWebClient") WebClient storageWebClient) {
        this.storageWebClient = storageWebClient;
    }

    public Mono<Void> delete(String key) {
        return storageWebClient.method(HttpMethod.DELETE)
                .uri("/api/storage/objects")
                .bodyValue(Map.of("key", key))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapError)
                .toBodilessEntity()
                .then();
    }

    private Mono<? extends Throwable> mapError(ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> {
                    log.warn("storage request failed: status={} body={}", status, body);
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "storage request failed"));
                });
    }
}
