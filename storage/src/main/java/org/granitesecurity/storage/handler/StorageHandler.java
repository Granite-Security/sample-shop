package org.granitesecurity.storage.handler;

import org.granitesecurity.storage.dto.DeleteObjectRequest;
import org.granitesecurity.storage.dto.PresignRequest;
import org.granitesecurity.storage.service.StorageService;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Service
public class StorageHandler {

    private final StorageService storageService;

    public StorageHandler(StorageService storageService) {
        this.storageService = storageService;
    }

    public Mono<ServerResponse> presign(ServerRequest request) {
        return request.bodyToMono(PresignRequest.class)
                .flatMap(req -> storageService.presign(req.fileName(), req.contentType(), req.scope()))
                .flatMap(response -> ServerResponse.ok().bodyValue(response));
    }

    public Mono<ServerResponse> deleteObject(ServerRequest request) {
        return request.bodyToMono(DeleteObjectRequest.class)
                .flatMap(req -> storageService.deleteObject(req.key()))
                .then(ServerResponse.noContent().build());
    }
}
