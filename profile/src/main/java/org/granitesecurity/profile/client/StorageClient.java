package org.granitesecurity.profile.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
public class StorageClient {

    private static final String USER_FILES_SCOPE = "user-files";

    private final WebClient storageWebClient;

    public StorageClient(@Qualifier("storageWebClient") WebClient storageWebClient) {
        this.storageWebClient = storageWebClient;
    }

    public Mono<StoragePresignResult> presign(String fileName, String contentType) {
        return storageWebClient.post()
                .uri("/api/storage/presign")
                .bodyValue(Map.of("fileName", fileName, "contentType", contentType, "scope", USER_FILES_SCOPE))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapError)
                .bodyToMono(StoragePresignResult.class);
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

    private Mono<? extends Throwable> mapError(org.springframework.web.reactive.function.client.ClientResponse response) {
        return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "storage request failed"));
    }
}
