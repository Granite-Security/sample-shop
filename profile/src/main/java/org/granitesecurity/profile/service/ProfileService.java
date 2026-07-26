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

@Service
public class ProfileService {

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
                profile.getLastName(),
                profile.getCreatedAt(),
                profile.getUpdatedAt()
        );
    }
}
