package org.granitesecurity.profile.service;

import org.granitesecurity.profile.client.StorageClient;
import org.granitesecurity.profile.domain.UserFile;
import org.granitesecurity.profile.dto.DuplicateFileCheckResponse;
import org.granitesecurity.profile.dto.PublicFileResponse;
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
            "image/jpeg", "image/png", "image/webp", "application/pdf", "text/plain",
            // Only the two formats every current browser plays natively, matching
            // the "products" scope in StorageService. QuickTime .mov is
            // deliberately absent: it uploads happily and then fails to play for
            // most visitors, which is worse than refusing it at file-pick time.
            "video/mp4", "video/webm");

    private static final Set<String> VIDEO_CONTENT_TYPES = Set.of("video/mp4", "video/webm");

    // A showcase clip, not an archive. The 5 GB ceiling below is the S3 protocol
    // limit, not a considered size for something 50 of which can sit on one
    // account: 50 x 5 GB would fill the node's disk on its own. Raise this
    // deliberately if real clips need more.
    private static final long MAX_VIDEO_SIZE_BYTES = 500_000_000L;

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

    /**
     * Publishes a file to, or removes it from, the owner's public profile.
     *
     * <p>A flag on the file row rather than a URL copied onto the profile: one source
     * of truth, so deleting the file takes it off the public page by itself instead of
     * leaving a dead link behind (docs/profile/public-profile.md §11).
     */
    public Mono<UserFileResponse> setShared(String username, Long id, Boolean shared) {
        if (shared == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "shared is required"));
        }
        return userFileRepository.findByIdAndUsername(id, username)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found")))
                .flatMap(file -> userFileRepository.updateShared(id, username, shared))
                .then(userFileRepository.findByIdAndUsername(id, username))
                .map(this::toResponse);
    }

    /** Anonymous listing behind /users/&lt;handle&gt;. Empty for an unpublished profile. */
    public Flux<PublicFileResponse> listPublicFiles(String handle) {
        String normalized = handle == null ? "" : handle.trim().toLowerCase(java.util.Locale.ROOT);
        return userFileRepository.findSharedByHandle(normalized)
                .map(file -> new PublicFileResponse(
                        file.getId(),
                        file.getFileName(),
                        file.getUrl(),
                        file.getContentType(),
                        file.getSizeBytes(),
                        file.getCreatedAt()));
    }

    // Called before the browser even starts the upload (the client hashes
    // the file locally) so a duplicate never gets uploaded at all, rather
    // than discovering it after the fact at register time.
    public Mono<DuplicateFileCheckResponse> checkDuplicate(String username, String contentHash) {
        return userFileRepository.findByUsernameAndContentHash(username, contentHash)
                .map(existing -> new DuplicateFileCheckResponse(true, toResponse(existing)))
                .defaultIfEmpty(new DuplicateFileCheckResponse(false, null));
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
        if (VIDEO_CONTENT_TYPES.contains(req.contentType())
                && req.sizeBytes() != null && req.sizeBytes() > MAX_VIDEO_SIZE_BYTES) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "video must not exceed " + (MAX_VIDEO_SIZE_BYTES / 1_000_000) + " MB"));
        }
        // contentHash is optional, and null is a supported state rather than a
        // gap: the browser computes it by reading the whole file into memory
        // (crypto.subtle.digest has no streaming form), which a large video would
        // not survive, so the client skips it above a size threshold. Migration
        // 004 already anticipated null hashes — Postgres treats NULLs as distinct
        // in the unique index, so unhashed rows never collide with each other.
        // The only cost is that such a file is not de-duplicated.
        boolean hashed = req.contentHash() != null && !req.contentHash().isBlank();
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
                    // Defense in depth against the client skipping (or losing a race
                    // around) the pre-upload checkDuplicate call — the DB unique
                    // index on (username, content_hash) is the real backstop, this
                    // just turns that into a friendly 409 instead of a raw
                    // constraint-violation 500.
                    if (!hashed) {
                        return Mono.just(false);
                    }
                    return userFileRepository.findByUsernameAndContentHash(username, req.contentHash())
                            .flatMap(dup -> Mono.<Boolean>error(new ResponseStatusException(
                                    HttpStatus.CONFLICT, "This file has already been uploaded.")))
                            .defaultIfEmpty(false);
                })
                .flatMap(ignored -> {
                    UserFile file = new UserFile();
                    file.setUsername(username);
                    file.setFileName(req.fileName());
                    file.setObjectKey(req.key());
                    file.setUrl(publicBaseUrl + "/" + req.key());
                    file.setContentType(req.contentType());
                    file.setSizeBytes(req.sizeBytes());
                    file.setContentHash(hashed ? req.contentHash() : null);
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
                file.isShared(),
                file.getCreatedAt()
        );
    }
}
