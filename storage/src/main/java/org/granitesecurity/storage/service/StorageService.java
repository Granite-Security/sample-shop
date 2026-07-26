package org.granitesecurity.storage.service;

import org.granitesecurity.storage.dto.PresignResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

@Service
public class StorageService {

    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");

    private static final Set<String> ALLOWED_SCOPES = Set.of("products");

    private static final Duration PRESIGN_EXPIRY = Duration.ofMinutes(10);

    private final S3Presigner s3Presigner;
    private final S3Client s3Client;
    private final String bucket;
    private final String publicBaseUrl;

    public StorageService(S3Presigner s3Presigner, S3Client s3Client,
                           @Value("${storage.s3.bucket}") String bucket,
                           @Value("${storage.s3.public-base-url}") String publicBaseUrl) {
        this.s3Presigner = s3Presigner;
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.publicBaseUrl = publicBaseUrl;
    }

    public Mono<PresignResponse> presign(String fileName, String contentType, String scope) {
        return Mono.fromCallable(() -> {
            if (fileName == null || fileName.isBlank()) {
                throw new StorageException("fileName is required");
            }
            if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
                throw new StorageException("contentType must be one of " + ALLOWED_CONTENT_TYPES);
            }
            if (scope == null || !ALLOWED_SCOPES.contains(scope)) {
                throw new StorageException("scope must be one of " + ALLOWED_SCOPES);
            }

            String key = scope + "/" + UUID.randomUUID() + "/" + sanitize(fileName);

            PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(PutObjectPresignRequest.builder()
                    .signatureDuration(PRESIGN_EXPIRY)
                    .putObjectRequest(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType)
                            .build())
                    .build());

            return new PresignResponse(
                    key,
                    presigned.url().toString(),
                    publicBaseUrl + "/" + key,
                    PRESIGN_EXPIRY.toSeconds());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> deleteObject(String key) {
        return Mono.<Void>fromRunnable(() -> {
            if (key == null || ALLOWED_SCOPES.stream().noneMatch(scope -> key.startsWith(scope + "/"))) {
                throw new StorageException("key must be prefixed by an allowed scope: " + ALLOWED_SCOPES);
            }
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String sanitize(String fileName) {
        String base = fileName.replaceAll(".*[/\\\\]", "");
        String sanitized = base.replaceAll("[^a-zA-Z0-9._-]", "_").replaceAll("^\\.+", "");
        return sanitized.isBlank() ? "file" : sanitized;
    }
}
