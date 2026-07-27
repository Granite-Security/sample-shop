package org.granitesecurity.profile.service;

import org.granitesecurity.profile.domain.UserProfile;
import org.granitesecurity.profile.dto.UpdateProfileRequest;
import org.granitesecurity.profile.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock
    private UserProfileRepository userProfileRepository;

    @InjectMocks
    private ProfileService profileService;

    @Test
    void listAllMapsAllRows() {
        var alice = new UserProfile("alice", "alice@example.com", "Alice", "Anderson");
        alice.setId(1L);
        var bob = new UserProfile("bob", "bob@example.com", "Bob", "Brown");
        bob.setId(2L);
        when(userProfileRepository.findAll()).thenReturn(Flux.just(alice, bob));

        StepVerifier.create(profileService.listAll())
                .expectNextMatches(p -> p.id() == 1L && p.username().equals("alice")
                        && p.email().equals("alice@example.com"))
                .expectNextMatches(p -> p.id() == 2L && p.username().equals("bob"))
                .verifyComplete();
    }

    @Test
    void getByUsernameReturnsDto() {
        var alice = new UserProfile("alice", "alice@example.com", "Alice", "Anderson");
        alice.setId(1L);
        when(userProfileRepository.findByUsername("alice")).thenReturn(Mono.just(alice));

        StepVerifier.create(profileService.getByUsername("alice"))
                .expectNextMatches(p -> p.id() == 1L && p.username().equals("alice")
                        && p.firstName().equals("Alice") && p.lastName().equals("Anderson"))
                .verifyComplete();
    }

    @Test
    void getByUsernameErrorsNotFoundWhenAbsent() {
        when(userProfileRepository.findByUsername("ghost")).thenReturn(Mono.empty());

        StepVerifier.create(profileService.getByUsername("ghost"))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    void updateProfileSetsValidDisplayName() {
        var alice = new UserProfile("alice", "alice@example.com", "Alice", "Anderson");
        alice.setId(1L);
        when(userProfileRepository.findByUsername("alice")).thenReturn(Mono.just(alice));
        when(userProfileRepository.save(alice)).thenReturn(Mono.just(alice));

        var req = new UpdateProfileRequest("alice@example.com", "Alice", "Anderson", "Adrian M");

        StepVerifier.create(profileService.updateProfile("alice", req))
                .expectNextMatches(p -> "Adrian M".equals(p.displayName()))
                .verifyComplete();
    }

    @Test
    void updateProfileBlankDisplayNameStoresNull() {
        var alice = new UserProfile("alice", "alice@example.com", "Alice", "Anderson");
        alice.setId(1L);
        when(userProfileRepository.findByUsername("alice")).thenReturn(Mono.just(alice));
        when(userProfileRepository.save(alice)).thenReturn(Mono.just(alice));

        var req = new UpdateProfileRequest("alice@example.com", "Alice", "Anderson", "   ");

        StepVerifier.create(profileService.updateProfile("alice", req))
                .expectNextMatches(p -> p.displayName() == null)
                .verifyComplete();
    }

    @Test
    void updateProfileRejectsInvalidDisplayName() {
        var alice = new UserProfile("alice", "alice@example.com", "Alice", "Anderson");
        alice.setId(1L);
        when(userProfileRepository.findByUsername("alice")).thenReturn(Mono.just(alice));

        var req = new UpdateProfileRequest("alice@example.com", "Alice", "Anderson", "a");

        StepVerifier.create(profileService.updateProfile("alice", req))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();
    }
}
