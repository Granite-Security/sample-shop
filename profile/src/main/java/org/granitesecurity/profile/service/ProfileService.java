package org.granitesecurity.profile.service;

import org.granitesecurity.profile.domain.UserProfile;
import org.granitesecurity.profile.dto.AvatarSource;
import org.granitesecurity.profile.dto.HandleAvailability;
import org.granitesecurity.profile.dto.PublicProfileResponse;
import org.granitesecurity.profile.dto.ProfileResponse;
import org.granitesecurity.profile.dto.UpdateProfileRequest;
import org.granitesecurity.profile.repository.UserProfileRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class ProfileService {

    private static final Pattern DISPLAY_NAME_PATTERN = Pattern.compile("^[\\p{L}\\p{N} ._'-]+$");

    // 3-32 chars, lowercase alphanumerics and hyphens, no leading or trailing hyphen
    // (docs/profile/public-profile.md step 4).
    private static final Pattern HANDLE_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$");

    // "me" and "admin" are load-bearing: both already shadow the {username} wildcard in
    // ProfileRoute and ProfileSec. The rest keep future /users/* SPA paths free.
    private static final Set<String> RESERVED_HANDLES = Set.of(
            "me", "admin", "contact", "internal", "public", "api", "auth", "users", "new",
            "edit", "settings", "login", "logout", "register", "null", "undefined");

    private static final int BIO_MAX = 500;

    private final UserProfileRepository userProfileRepository;

    public ProfileService(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
    }

    public Flux<ProfileResponse> listAll() {
        return userProfileRepository.findAll()
                .map(ProfileService::toResponse);
    }

    public Mono<ProfileResponse> getByUsername(String username) {
        return userProfileRepository.findByUsername(username)
                .switchIfEmpty(Mono.error(
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found: " + username)))
                .map(ProfileService::toResponse);
    }

    public Mono<ProfileResponse> getProfile(String username) {
        return getProfile(username, null);
    }

    /**
     * @param googlePictureUrl the {@code picture} claim off the caller's token, or
     *                         null for a form login. This is how Google's picture
     *                         reaches this service at all (docs/users/user-pic.md D1):
     *                         auth-server forwards the claim, and the value is cached
     *                         here so admin views and other clients can read it from
     *                         the profile row rather than needing the user's token.
     */
    public Mono<ProfileResponse> getProfile(String username, String googlePictureUrl) {
        return findOrCreate(username)
                .flatMap(profile -> syncGooglePicture(profile, googlePictureUrl))
                .map(ProfileService::toResponse);
    }

    /**
     * Refreshes the cached Google picture when the token carries a different one —
     * which is what makes a changed Google photo appear after the next sign-in
     * without any polling or event.
     *
     * <p>Writes through a targeted UPDATE of the two columns it owns rather than
     * saving the whole entity. The entity in hand may have been read before a
     * concurrent writer (the UserRegistered consumer) filled in email/first/last, and
     * a full-row save would write those back as null — which is exactly how a
     * registered user ended up with no email on their profile.
     */
    private Mono<UserProfile> syncGooglePicture(UserProfile profile, String googlePictureUrl) {
        if (googlePictureUrl == null || googlePictureUrl.isBlank()
                || googlePictureUrl.equals(profile.getGooglePictureUrl())) {
            return Mono.just(profile);
        }
        return userProfileRepository.syncGooglePicture(profile.getUsername(), googlePictureUrl)
                // Re-read so the response carries both this write and anything the
                // other writer committed in the meantime.
                .then(userProfileRepository.findByUsername(profile.getUsername()))
                .defaultIfEmpty(profile);
    }

    public Mono<ProfileResponse> updateProfile(String username, UpdateProfileRequest req) {
        return userProfileRepository.findByUsername(username)
                .switchIfEmpty(Mono.defer(() -> createProfile(username)))
                .flatMap(profile -> {
                    profile.setEmail(req.email());
                    profile.setFirstName(req.firstName());
                    profile.setLastName(req.lastName());
                    profile.setDisplayName(validateDisplayName(req.displayName()));
                    profile.setBio(validateBio(req.bio()));
                    profile.setUpdatedAt(Instant.now());
                    return userProfileRepository.save(profile);
                })
                .map(ProfileService::toResponse);
    }

    private String validateDisplayName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() < 2 || trimmed.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "displayName must be between 2 and 64 characters");
        }
        if (!DISPLAY_NAME_PATTERN.matcher(trimmed).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "displayName contains invalid characters");
        }
        return trimmed;
    }

    /**
     * The anonymous read behind {@code /users/<handle>}.
     *
     * <p>An unknown handle and an unpublished one both answer 404, deliberately: a 403
     * would confirm the handle exists, turning this into a membership oracle over a
     * namespace people pick to be memorable (docs/profile/public-profile.md D4).
     */
    public Mono<PublicProfileResponse> getPublishedByHandle(String handle) {
        String normalized = handle == null ? "" : handle.trim().toLowerCase(Locale.ROOT);
        return userProfileRepository.findPublishedByHandle(normalized)
                .switchIfEmpty(Mono.error(
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not available")))
                .map(ProfileService::toPublicResponse);
    }

    /**
     * Sets or changes the caller's handle.
     *
     * <p>Conflicts are detected by letting uq_user_profile_handle reject the write, not
     * by a pre-flight SELECT — two users racing for the same handle then have a
     * deterministic winner instead of both passing a check.
     */
    public Mono<ProfileResponse> setHandle(String username, String rawHandle) {
        return Mono.defer(() -> {
            String handle = validateHandle(rawHandle);
            return findOrCreate(username)
                    .flatMap(profile -> userProfileRepository.updateHandle(username, handle))
                    .onErrorMap(DuplicateKeyException.class, e -> new ResponseStatusException(
                            HttpStatus.CONFLICT, "handle is already taken"))
                    .then(userProfileRepository.findByUsername(username))
                    .map(ProfileService::toResponse);
        });
    }

    /**
     * Publishes or unpublishes. A pure boolean flip that cannot conflict, because the
     * handle was already reserved when it was set (D2).
     */
    public Mono<ProfileResponse> setVisibility(String username, Boolean publicProfile) {
        if (publicProfile == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "publicProfile is required"));
        }
        return findOrCreate(username)
                .flatMap(profile -> {
                    if (publicProfile && (profile.getHandle() == null || profile.getHandle().isBlank())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Choose a handle before making your profile public"));
                    }
                    return userProfileRepository.updateVisibility(username, publicProfile);
                })
                .then(userProfileRepository.findByUsername(username))
                .map(ProfileService::toResponse);
    }

    /**
     * Availability for the owner's form. Stays behind authentication — an anonymous
     * version of this is a free enumeration oracle over the whole namespace.
     */
    public Mono<HandleAvailability> checkHandle(String username, String rawHandle) {
        String normalized = rawHandle == null ? "" : rawHandle.trim().toLowerCase(Locale.ROOT);
        try {
            validateHandle(normalized);
        } catch (ResponseStatusException e) {
            return Mono.just(new HandleAvailability(normalized, false, e.getReason()));
        }
        return userProfileRepository.countHandleTakenByOthers(normalized, username)
                .map(count -> count == 0
                        ? new HandleAvailability(normalized, true, null)
                        : new HandleAvailability(normalized, false, "handle is already taken"));
    }

    /** Admin force-unpublish, and what blocking a user triggers (D6). */
    public Mono<Void> unpublish(String username) {
        return userProfileRepository.unpublish(username).then();
    }

    private String validateHandle(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "handle is required");
        }
        String handle = raw.trim().toLowerCase(Locale.ROOT);
        if (!HANDLE_PATTERN.matcher(handle).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "handle must be 3-32 characters: lowercase letters, digits and hyphens, "
                            + "not starting or ending with a hyphen");
        }
        if (RESERVED_HANDLES.contains(handle)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "handle is reserved");
        }
        return handle;
    }

    /**
     * No HTML sanitising, because no HTML is ever rendered: React escapes by default and
     * the public page must not use dangerouslySetInnerHTML. Control characters go because
     * they have no business in a one-paragraph bio.
     */
    private String validateBio(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > BIO_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "bio must be at most " + BIO_MAX + " characters");
        }
        if (trimmed.chars().anyMatch(c -> c < 0x20 && c != '\n' && c != '\r' && c != '\t')) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "bio contains invalid characters");
        }
        return trimmed;
    }

    /**
     * Creates or completes a profile from a UserRegistered event.
     *
     * <p>Only fills fields that are currently null, never overwrites — done as a
     * single INSERT .. ON CONFLICT DO UPDATE with COALESCE rather than
     * load-modify-save. Both matter, for different reasons. "Never overwrite" means a
     * redelivered event cannot undo details the user has since edited. Doing it in one
     * statement means the concurrent Google-picture write, which reads and writes the
     * same row from the HTTP path, touches disjoint columns instead of racing this one
     * — the earlier version lost the email whenever that write won.
     */
    public Mono<UserProfile> provisionFromRegistration(String username, String email,
                                                       String firstName, String lastName) {
        return userProfileRepository.upsertFromRegistration(username, email, firstName, lastName)
                .then(userProfileRepository.findByUsername(username));
    }

    /**
     * Idempotent: two writers racing to create the same profile both succeed, and the
     * loser reads the winner's row instead of failing on user_profile_username_key.
     */
    private Mono<UserProfile> createProfile(String username) {
        return userProfileRepository.insertIfAbsent(username)
                .then(userProfileRepository.findByUsername(username));
    }

    /** Shared with AvatarService: an avatar can be set before "My Profile" is ever opened. */
    public Mono<UserProfile> findOrCreate(String username) {
        return userProfileRepository.findByUsername(username)
                .switchIfEmpty(Mono.defer(() -> createProfile(username)));
    }

    /**
     * The one place the three-state avatar collapses into a single URL, so no
     * client re-derives it. Null means "draw the initials monogram".
     */
    public static String effectiveAvatarUrl(UserProfile profile) {
        return switch (AvatarSource.parse(profile.getAvatarSource())) {
            case UPLOAD -> profile.getUploadedAvatarUrl();
            case GOOGLE -> profile.getGooglePictureUrl();
            case NONE -> null;
        };
    }

    public static PublicProfileResponse toPublicResponse(UserProfile profile) {
        return new PublicProfileResponse(
                profile.getHandle(),
                profile.getUsername(),
                profile.getDisplayName() != null ? profile.getDisplayName() : profile.getHandle(),
                effectiveAvatarUrl(profile),
                profile.getBio(),
                profile.getCreatedAt()
        );
    }

    public static ProfileResponse toResponse(UserProfile profile) {
        return new ProfileResponse(
                profile.getId(),
                profile.getUsername(),
                profile.getEmail(),
                profile.getFirstName(),
                profile.getLastName(),
                profile.getDisplayName(),
                profile.getHandle(),
                profile.getBio(),
                profile.isPublicProfile(),
                effectiveAvatarUrl(profile),
                AvatarSource.parse(profile.getAvatarSource()).name(),
                profile.getUploadedAvatarUrl(),
                profile.getGooglePictureUrl(),
                profile.getCreatedAt(),
                profile.getUpdatedAt()
        );
    }
}
