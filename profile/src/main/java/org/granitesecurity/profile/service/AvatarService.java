package org.granitesecurity.profile.service;

import org.granitesecurity.profile.client.StorageClient;
import org.granitesecurity.profile.domain.UserProfile;
import org.granitesecurity.profile.dto.AvatarSource;
import org.granitesecurity.profile.dto.AvatarSourceRequest;
import org.granitesecurity.profile.dto.ProfileResponse;
import org.granitesecurity.profile.dto.RegisterAvatarRequest;
import org.granitesecurity.profile.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Set;

/**
 * The avatar half of a profile (docs/users/user-pic.md).
 *
 * <p>Deliberately not folded into {@code PUT /api/profiles/me}: that endpoint
 * overwrites every field it is given, so every client saving the details form
 * would have to round-trip the avatar fields correctly or blank them (D4).
 */
@Service
public class AvatarService {

    private static final Logger log = LoggerFactory.getLogger(AvatarService.class);

    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");

    // The browser downscales to 512x512 before uploading, so this is a backstop
    // against a client that doesn't, not the primary control. It can only ever
    // be advisory: the bytes are already in storage by the time we see this,
    // since the upload goes browser -> storage under a presigned URL. The Garage
    // bucket quota is the real ceiling, exactly as for user files.
    private static final long MAX_SIZE_BYTES = 2_000_000L;

    private static final String KEY_PREFIX = "avatars/";

    private final UserProfileRepository userProfileRepository;
    private final ProfileService profileService;
    private final StorageClient storageClient;

    public AvatarService(UserProfileRepository userProfileRepository, ProfileService profileService,
                         StorageClient storageClient) {
        this.userProfileRepository = userProfileRepository;
        this.profileService = profileService;
        this.storageClient = storageClient;
    }

    public Mono<ProfileResponse> register(String username, RegisterAvatarRequest req) {
        if (req.key() == null || !req.key().startsWith(KEY_PREFIX)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "key must be prefixed by " + KEY_PREFIX));
        }
        if (req.url() == null || req.url().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "url is required"));
        }
        if (req.contentType() == null || !ALLOWED_CONTENT_TYPES.contains(req.contentType())) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "contentType must be one of " + ALLOWED_CONTENT_TYPES));
        }
        if (req.sizeBytes() != null && req.sizeBytes() > MAX_SIZE_BYTES) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "sizeBytes must not exceed " + MAX_SIZE_BYTES));
        }

        return profileService.findOrCreate(username)
                .flatMap(profile -> {
                    String previousKey = profile.getAvatarObjectKey();
                    profile.setAvatarObjectKey(req.key());
                    profile.setUploadedAvatarUrl(req.url());
                    profile.setAvatarSource(AvatarSource.UPLOAD.name());
                    profile.setUpdatedAt(Instant.now());
                    return userProfileRepository.save(profile)
                            .flatMap(saved -> deleteQuietly(previousKey, req.key()).thenReturn(saved));
                })
                .map(ProfileService::toResponse);
    }

    public Mono<ProfileResponse> setSource(String username, AvatarSourceRequest req) {
        AvatarSource requested = AvatarSource.parse(req.source());
        if (req.source() != null && !req.source().isBlank()
                && !requested.name().equalsIgnoreCase(req.source().trim())) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "source must be one of UPLOAD, GOOGLE, NONE"));
        }

        return profileService.findOrCreate(username)
                .flatMap(profile -> {
                    // Refuse rather than silently landing on a source with nothing
                    // behind it — the effective URL must never be null while the
                    // source claims a picture exists.
                    if (requested == AvatarSource.UPLOAD && isBlank(profile.getUploadedAvatarUrl())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "no uploaded picture to switch to"));
                    }
                    if (requested == AvatarSource.GOOGLE && isBlank(profile.getGooglePictureUrl())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "no Google picture for this account"));
                    }
                    profile.setAvatarSource(requested.name());
                    profile.setUpdatedAt(Instant.now());
                    return userProfileRepository.save(profile);
                })
                .map(ProfileService::toResponse);
    }

    /**
     * Removes the uploaded picture and falls back to Google's if there is one.
     * The Google picture itself is never "removed" — it is a cache of a claim
     * that arrives on every sign-in, so deleting it would just come straight
     * back. A user who wants no picture at all sets the source to NONE.
     */
    public Mono<ProfileResponse> remove(String username) {
        return profileService.findOrCreate(username)
                .flatMap(profile -> {
                    String key = profile.getAvatarObjectKey();
                    profile.setAvatarObjectKey(null);
                    profile.setUploadedAvatarUrl(null);
                    profile.setAvatarSource(isBlank(profile.getGooglePictureUrl())
                            ? AvatarSource.NONE.name()
                            : AvatarSource.GOOGLE.name());
                    profile.setUpdatedAt(Instant.now());
                    return userProfileRepository.save(profile)
                            .flatMap(saved -> deleteQuietly(key, null).thenReturn(saved));
                })
                .map(ProfileService::toResponse);
    }

    /**
     * Deletes the object a profile no longer points at, after the row has been
     * saved. Failures are logged, not propagated: the user's avatar has already
     * changed as far as they are concerned, and a storage hiccup must not turn
     * that into an error. The cost of the failure is one orphaned object.
     */
    private Mono<Void> deleteQuietly(String key, String replacementKey) {
        if (key == null || key.isBlank() || key.equals(replacementKey)) {
            return Mono.empty();
        }
        return storageClient.delete(key)
                .onErrorResume(e -> {
                    log.warn("failed to delete replaced avatar object {}: {}", key, e.getMessage());
                    return Mono.empty();
                });
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
