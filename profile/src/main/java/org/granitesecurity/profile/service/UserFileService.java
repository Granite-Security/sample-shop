package org.granitesecurity.profile.service;

import org.granitesecurity.profile.client.StorageClient;
import org.granitesecurity.profile.domain.UserFile;
import org.granitesecurity.profile.dto.RegisterFileRequest;
import org.granitesecurity.profile.dto.UserFileResponse;
import org.granitesecurity.profile.repository.UserFileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Set;

@Service
public class UserFileService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "application/pdf", "text/plain");

    // S3 (and Garage, which implements the same API) rejects a single PUT
    // above 5 GiB — anything larger needs multipart upload, which this
    // presign-based flow doesn't support. Stay comfortably under that hard
    // ceiling rather than at it.
    private static final long MAX_SIZE_BYTES = 5_000_000_000L;

    private static final long MAX_FILES_PER_USER = 50;

    private static final String KEY_PREFIX = "user-files/";

    private final UserFileRepository userFileRepository;
    private final StorageClient storageClient;
    private final String publicBaseUrl;

    public UserFileService(UserFileRepository userFileRepository, StorageClient storageClient,
                            @Value("${storage.public-base-url}") String publicBaseUrl) {
        this.userFileRepository = userFileRepository;
        this.storageClient = storageClient;
        this.publicBaseUrl = publicBaseUrl;
    }

    public Flux<UserFileResponse> listFiles(String username) {
        return userFileRepository.findByUsernameOrderByCreatedAtDesc(username)
                .map(this::toResponse);
    }

    // Upload itself (presign) now goes straight from the browser to storage
    // (mirroring the admin product-media upload flow) rather than through a
    // profile-brokered client-credentials call — storage enforces the
    // content-type allow-list per scope at that point. What profile still
    // validates here at register time is everything storage can't know:
    // per-user file count, and (best-effort, since the object is already
    // uploaded by now) the declared size/type look sane. A rejected
    // registration can leave an orphaned object in storage; the Garage
    // bucket quota is the real backstop for that, same as the presign-time
    // size guard always was advisory only.
    public Mono<UserFileResponse> register(String username, RegisterFileRequest req) {
        if (req.key() == null || !req.key().startsWith(KEY_PREFIX)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "key must be prefixed by " + KEY_PREFIX));
        }
        if (req.contentType() == null || !ALLOWED_CONTENT_TYPES.contains(req.contentType())) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "contentType must be one of " + ALLOWED_CONTENT_TYPES));
        }
        if (req.sizeBytes() != null && req.sizeBytes() > MAX_SIZE_BYTES) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "sizeBytes must not exceed " + MAX_SIZE_BYTES));
        }
        return userFileRepository.countByUsername(username)
                .flatMap(count -> {
                    if (count >= MAX_FILES_PER_USER) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "maximum of " + MAX_FILES_PER_USER + " files reached"));
                    }
                    return userFileRepository.existsByObjectKey(req.key());
                })
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                "file already registered"));
                    }
                    UserFile file = new UserFile();
                    file.setUsername(username);
                    file.setFileName(req.fileName());
                    file.setObjectKey(req.key());
                    file.setUrl(publicBaseUrl + "/" + req.key());
                    file.setContentType(req.contentType());
                    file.setSizeBytes(req.sizeBytes());
                    file.setCreatedAt(Instant.now());
                    return userFileRepository.save(file);
                })
                .map(this::toResponse);
    }

    public Mono<Void> delete(Long id, String username) {
        return userFileRepository.findByIdAndUsername(id, username)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "file not found")))
                .flatMap(file -> storageClient.delete(file.getObjectKey())
                        .then(Mono.defer(() -> userFileRepository.delete(file)))
                        .onErrorMap(e -> !(e instanceof ResponseStatusException), e ->
                                new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                                        "failed to delete file from storage")));
    }

    private UserFileResponse toResponse(UserFile file) {
        return new UserFileResponse(
                file.getId(),
                file.getFileName(),
                file.getUrl(),
                file.getContentType(),
                file.getSizeBytes(),
                file.getCreatedAt()
        );
    }
}
