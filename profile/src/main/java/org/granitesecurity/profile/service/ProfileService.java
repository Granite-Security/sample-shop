package org.granitesecurity.profile.service;

import org.granitesecurity.profile.domain.UserProfile;
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
                .map(this::toResponse);
    }

    public Mono<ProfileResponse> getByUsername(String username) {
        return userProfileRepository.findByUsername(username)
                .switchIfEmpty(Mono.error(
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found: " + username)))
                .map(this::toResponse);
    }

    public Mono<ProfileResponse> getProfile(String username) {
        return userProfileRepository.findByUsername(username)
                .switchIfEmpty(createProfile(username))
                .map(this::toResponse);
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
                .map(this::toResponse);
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

    private Mono<UserProfile> createProfile(String username) {
        UserProfile profile = new UserProfile(username, null, null, null);
        profile.setCreatedAt(Instant.now());
        profile.setUpdatedAt(Instant.now());
        return userProfileRepository.save(profile);
    }

    private ProfileResponse toResponse(UserProfile profile) {
        return new ProfileResponse(
                profile.getId(),
                profile.getUsername(),
                profile.getEmail(),
                profile.getFirstName(),
                profile.getLastName(),
                profile.getDisplayName(),
                profile.getCreatedAt(),
                profile.getUpdatedAt()
        );
    }
}
