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

import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class StorageService {

    private static final String USER_FILES_SCOPE = "user-files";

    private static final String AVATARS_SCOPE = "avatars";

    // Scopes a plain logged-in user may sign and delete keys under. Everything
    // else (today: "products") is ADMIN/MANAGER only.
    private static final Set<String> SELF_SERVICE_SCOPES = Set.of(USER_FILES_SCOPE, AVATARS_SCOPE);

    private static final Map<String, Set<String>> ALLOWED_CONTENT_TYPES_BY_SCOPE = Map.of(
            "products", Set.of("image/jpeg", "image/png", "image/webp"),
            USER_FILES_SCOPE, Set.of("image/jpeg", "image/png", "image/webp",
                    "application/pdf", "text/plain"),
            // Its own scope rather than a corner of user-files: an avatar in the
            // file cabinet would be a stray image counting against the 50-file
            // limit, and would inherit application/pdf and a 5 GB ceiling
            // (docs/users/user-pic.md D2).
            AVATARS_SCOPE, Set.of("image/jpeg", "image/png", "image/webp")
    );

    // Long enough for a multi-GB upload on a slow connection to finish before
    // the presigned URL expires mid-transfer (10 minutes was fine for small
    // product images, not for the raised 5 GB user-files limit).
    private static final Duration PRESIGN_EXPIRY = Duration.ofHours(2);

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

    public Mono<PresignResponse> presign(String fileName, String contentType, String scope,
                                          Collection<String> authorities, String username) {
        return Mono.fromCallable(() -> {
            if (fileName == null || fileName.isBlank()) {
                throw new StorageException("fileName is required");
            }
            Set<String> allowedContentTypes = scope == null ? null : ALLOWED_CONTENT_TYPES_BY_SCOPE.get(scope);
            if (allowedContentTypes == null) {
                throw new StorageException("scope must be one of " + ALLOWED_CONTENT_TYPES_BY_SCOPE.keySet());
            }
            requireScopeAllowedForCaller(scope, authorities);
            if (contentType == null || !allowedContentTypes.contains(contentType)) {
                throw new StorageException("contentType must be one of " + allowedContentTypes);
            }

            // Only the per-user scopes get a per-uploader folder — products has
            // no per-admin ownership concept, so a username segment there would
            // just be noise. The bucket is public, so this does mean a
            // user's own username is visible in their own file URLs; that's
            // an accepted, deliberate trade-off for browsable per-user
            // folders in Garage, not an oversight.
            String prefix = SELF_SERVICE_SCOPES.contains(scope) ? scope + "/" + sanitizeSegment(username) : scope;
            String key = prefix + "/" + UUID.randomUUID() + "/" + sanitize(fileName);

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

    public Mono<Void> deleteObject(String key, Collection<String> authorities) {
        return Mono.<Void>fromRunnable(() -> {
            String scope = key == null ? null : ALLOWED_CONTENT_TYPES_BY_SCOPE.keySet().stream()
                    .filter(s -> key.startsWith(s + "/"))
                    .findFirst()
                    .orElse(null);
            if (scope == null) {
                throw new StorageException(
                        "key must be prefixed by an allowed scope: " + ALLOWED_CONTENT_TYPES_BY_SCOPE.keySet());
            }
            requireScopeAllowedForCaller(scope, authorities);
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void requireScopeAllowedForCaller(String scope, Collection<String> authorities) {
        Collection<String> auths = authorities == null ? Set.of() : authorities;
        boolean isElevated = auths.contains("ROLE_ADMIN") || auths.contains("ROLE_MANAGER");
        if (isElevated) {
            return;
        }
        // Any other authenticated caller (a plain logged-in user, not just an
        // internal service) is confined to the self-service scopes — only
        // ADMIN/MANAGER may sign/delete product-media keys.
        if (!SELF_SERVICE_SCOPES.contains(scope)) {
            throw new StorageException(
                    "caller is not authorized to use scope: " + scope, HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private String sanitize(String fileName) {
        String base = fileName.replaceAll(".*[/\\\\]", "");
        String sanitized = base.replaceAll("[^a-zA-Z0-9._-]", "_").replaceAll("^\\.+", "");
        return sanitized.isBlank() ? "file" : sanitized;
    }

    // Usernames are already restricted to [a-zA-Z0-9._-] at registration, but
    // this key ends up in a public URL, so treat it as untrusted input here
    // too rather than relying solely on that upstream validation.
    private String sanitizeSegment(String value) {
        if (value == null) {
            return "user";
        }
        // Replace disallowed chars first, then collapse any run of 2+ dots
        // (wherever it lands, not just at the start) so a value like
        // "../../etc/passwd" can't reassemble a ".." traversal segment
        // after slashes are turned into underscores.
        String sanitized = value.replaceAll("[^a-zA-Z0-9._-]", "_").replaceAll("\\.{2,}", "_");
        return sanitized.isBlank() ? "user" : sanitized;
    }
}
