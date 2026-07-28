package org.granitesecurity.profile.service;

import org.granitesecurity.profile.domain.UserProfile;
import org.granitesecurity.profile.dto.AvatarSource;
import org.granitesecurity.profile.dto.ProfileResponse;
import org.granitesecurity.profile.dto.UpdateProfileRequest;
import org.granitesecurity.profile.repository.UserProfileRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.regex.Pattern;

@Service
public class ProfileService {

    private static final Pattern DISPLAY_NAME_PATTERN = Pattern.compile("^[\\p{L}\\p{N} ._'-]+$");

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
        return userProfileRepository.findByUsername(username)
                .switchIfEmpty(createProfile(username))
                .flatMap(profile -> syncGooglePicture(profile, googlePictureUrl))
                .map(ProfileService::toResponse);
    }

    /**
     * Refreshes the cached Google picture when the token carries a different one —
     * which is what makes a changed Google photo appear after the next sign-in
     * without any polling or event.
     *
     * <p>The source is flipped to GOOGLE only when this is the <em>first</em> picture
     * we have ever seen for them. Once {@code googlePictureUrl} is set, a later NONE
     * is a choice the user made on the profile page, and refreshing the URL must not
     * quietly undo it.
     */
    private Mono<UserProfile> syncGooglePicture(UserProfile profile, String googlePictureUrl) {
        if (googlePictureUrl == null || googlePictureUrl.isBlank()
                || googlePictureUrl.equals(profile.getGooglePictureUrl())) {
            return Mono.just(profile);
        }
        boolean firstSighting = isBlank(profile.getGooglePictureUrl());
        profile.setGooglePictureUrl(googlePictureUrl);
        if (firstSighting && AvatarSource.NONE.name().equals(profile.getAvatarSource())) {
            profile.setAvatarSource(AvatarSource.GOOGLE.name());
        }
        profile.setUpdatedAt(Instant.now());
        return userProfileRepository.save(profile);
    }

    public Mono<ProfileResponse> updateProfile(String username, UpdateProfileRequest req) {
        return userProfileRepository.findByUsername(username)
                .switchIfEmpty(Mono.defer(() -> createProfile(username)))
                .flatMap(profile -> {
                    profile.setEmail(req.email());
                    profile.setFirstName(req.firstName());
                    profile.setLastName(req.lastName());
                    profile.setDisplayName(validateDisplayName(req.displayName()));
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
     * Creates or completes a profile from a UserRegistered event.
     *
     * <p>Only fills fields that are currently null, never overwrites. That matters for
     * the race where the user opens "My Profile" before the event is consumed: the
     * lazy {@link #createProfile} has already written a username-only stub, and this
     * fills in the blanks rather than fighting it. It equally means a user who has
     * since edited their details keeps them if the event is ever redelivered.
     */
    public Mono<UserProfile> provisionFromRegistration(String username, String email,
                                                       String firstName, String lastName) {
        return userProfileRepository.findByUsername(username)
                .switchIfEmpty(Mono.defer(() -> createProfile(username)))
                .flatMap(profile -> {
                    boolean changed = false;
                    if (isBlank(profile.getEmail()) && !isBlank(email)) {
                        profile.setEmail(email);
                        changed = true;
                    }
                    if (isBlank(profile.getFirstName()) && !isBlank(firstName)) {
                        profile.setFirstName(firstName);
                        changed = true;
                    }
                    if (isBlank(profile.getLastName()) && !isBlank(lastName)) {
                        profile.setLastName(lastName);
                        changed = true;
                    }
                    if (!changed) {
                        return Mono.just(profile);
                    }
                    profile.setUpdatedAt(Instant.now());
                    return userProfileRepository.save(profile);
                });
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Mono<UserProfile> createProfile(String username) {
        UserProfile profile = new UserProfile(username, null, null, null);
        profile.setCreatedAt(Instant.now());
        profile.setUpdatedAt(Instant.now());
        return userProfileRepository.save(profile);
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

    public static ProfileResponse toResponse(UserProfile profile) {
        return new ProfileResponse(
                profile.getId(),
                profile.getUsername(),
                profile.getEmail(),
                profile.getFirstName(),
                profile.getLastName(),
                profile.getDisplayName(),
                effectiveAvatarUrl(profile),
                AvatarSource.parse(profile.getAvatarSource()).name(),
                profile.getUploadedAvatarUrl(),
                profile.getGooglePictureUrl(),
                profile.getCreatedAt(),
                profile.getUpdatedAt()
        );
    }
}
