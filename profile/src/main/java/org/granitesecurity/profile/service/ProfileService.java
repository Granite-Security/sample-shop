package org.granitesecurity.profile.service;

import org.granitesecurity.profile.domain.UserProfile;
import org.granitesecurity.profile.dto.ProfileResponse;
import org.granitesecurity.profile.dto.UpdateProfileRequest;
import org.granitesecurity.profile.repository.UserProfileRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Service
public class ProfileService {

    private final UserProfileRepository userProfileRepository;

    public ProfileService(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
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
                    profile.setUpdatedAt(Instant.now());
                    return userProfileRepository.save(profile);
                })
                .map(this::toResponse);
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
                profile.getLastName()
        );
    }
}
