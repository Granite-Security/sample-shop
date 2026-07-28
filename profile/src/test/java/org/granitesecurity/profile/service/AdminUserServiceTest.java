package org.granitesecurity.profile.service;

import org.granitesecurity.profile.client.IdentityAdminClient;
import org.granitesecurity.profile.client.ShopAdminClient;
import org.granitesecurity.profile.domain.AdminAction;
import org.granitesecurity.profile.domain.UserProfile;
import org.granitesecurity.profile.dto.AuthUser;
import org.granitesecurity.profile.dto.DeleteUserResult;
import org.granitesecurity.profile.dto.PurgeEligibility;
import org.granitesecurity.profile.dto.PurgeResult;
import org.granitesecurity.profile.repository.AdminActionRepository;
import org.granitesecurity.profile.repository.DeliveryAddressRepository;
import org.granitesecurity.profile.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminUserServiceTest {

    private IdentityAdminClient identityAdminClient;
    private ShopAdminClient shopAdminClient;
    private UserProfileRepository userProfileRepository;
    private DeliveryAddressRepository deliveryAddressRepository;
    private AdminActionRepository adminActionRepository;
    private AdminUserService service;

    @BeforeEach
    void setUp() {
        identityAdminClient = mock(IdentityAdminClient.class);
        shopAdminClient = mock(ShopAdminClient.class);
        userProfileRepository = mock(UserProfileRepository.class);
        deliveryAddressRepository = mock(DeliveryAddressRepository.class);
        adminActionRepository = mock(AdminActionRepository.class);
        // Pass-through: transactional boundaries are not what these tests verify.
        TransactionalOperator transactionalOperator = mock(TransactionalOperator.class);
        when(transactionalOperator.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = new AdminUserService(identityAdminClient, shopAdminClient, userProfileRepository,
                deliveryAddressRepository, adminActionRepository, transactionalOperator);

        when(adminActionRepository.save(any(AdminAction.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(deliveryAddressRepository.findByUsername(anyString())).thenReturn(Flux.empty());
        when(userProfileRepository.findByUsername(anyString())).thenReturn(Mono.empty());
    }

    private AuthUser user(String username, boolean enabled, String... roles) {
        return new AuthUser(1L, username, username + "@example.com", "First", "Last",
                enabled, "LOCAL", null, List.of(roles), null, null, null);
    }

    private void givenUsers(AuthUser... users) {
        when(identityAdminClient.listUsers()).thenReturn(Flux.just(users));
    }

    private void givenBlockSucceeds(String username) {
        when(identityAdminClient.block(org.mockito.ArgumentMatchers.eq(username), anyString()))
                .thenReturn(Mono.just(user(username, false, "ROLE_USER")));
    }

    // ── Guard rails (§7) ────────────────────────────────────────────

    @Test
    void anAdminCannotBlockThemselves() {
        StepVerifier.create(service.block("admin", "admin"))
                .expectErrorSatisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT))
                .verify();

        verify(identityAdminClient, never()).block(anyString(), anyString());
    }

    @Test
    void anAdminCannotDeleteThemselves() {
        StepVerifier.create(service.delete("admin", "admin"))
                .expectErrorSatisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT))
                .verify();

        verify(identityAdminClient, never()).delete(anyString());
    }

    // Without this, one click locks everyone out of the admin UI with no
    // recovery short of SQL.
    @Test
    void theLastEnabledAdminCannotBeBlocked() {
        // "victim" is the only enabled admin: "sleeping" is an admin but
        // blocked, so blocking "victim" would leave nobody able to get in.
        givenUsers(user("victim", true, "ROLE_ADMIN"),
                user("sleeping", false, "ROLE_ADMIN"),
                user("alice", true, "ROLE_USER"));

        StepVerifier.create(service.block("victim", "someoneelse"))
                .expectErrorSatisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT))
                .verify();

        verify(identityAdminClient, never()).block(anyString(), anyString());
    }

    // A blocked admin does not count towards "there is still someone who can
    // get in", which is the whole point of the guard.
    @Test
    void aDisabledAdminDoesNotCountAsARemainingAdmin() {
        givenUsers(user("victim", true, "ROLE_ADMIN"), user("blocked", false, "ROLE_ADMIN"));

        StepVerifier.create(service.delete("victim", "someoneelse"))
                .expectError(ResponseStatusException.class)
                .verify();
    }

    @Test
    void anAdminCanBeBlockedWhenAnotherEnabledAdminRemains() {
        givenUsers(user("victim", true, "ROLE_ADMIN"), user("survivor", true, "ROLE_ADMIN"));
        givenBlockSucceeds("victim");

        StepVerifier.create(service.block("victim", "survivor"))
                .expectNextCount(1)
                .verifyComplete();

        verify(identityAdminClient).block("victim", "survivor");
    }

    @Test
    void aNonAdminIsNotProtectedByTheLastAdminGuard() {
        givenUsers(user("alice", true, "ROLE_USER"), user("admin", true, "ROLE_ADMIN"));
        givenBlockSucceeds("alice");

        StepVerifier.create(service.block("alice", "admin"))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void deletingAUserThatDoesNotExistIsNotASilentSuccess() {
        givenUsers(user("admin", true, "ROLE_ADMIN"));

        StepVerifier.create(service.delete("ghost", "admin"))
                .expectErrorSatisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT))
                .verify();

        verify(identityAdminClient, never()).delete(anyString());
    }

    // ── Delete: block → check → delete-or-report (§4.2) ─────────────

    @Test
    void aPaidUserIsBlockedInsteadOfDeleted() {
        givenUsers(user("alice", true, "ROLE_USER"), user("admin", true, "ROLE_ADMIN"));
        givenBlockSucceeds("alice");
        when(shopAdminClient.purgeEligibility("alice"))
                .thenReturn(Mono.just(new PurgeEligibility(false, List.of(1L, 2L), 9)));

        StepVerifier.create(service.delete("alice", "admin"))
                .assertNext(result -> {
                    assertThat(result.outcome()).isEqualTo(DeleteUserResult.BLOCKED_INSTEAD);
                    assertThat(result.paidOrderCount()).isEqualTo(9);
                })
                .verifyComplete();

        // The block stands — that IS the outcome, not a failed cleanup.
        verify(identityAdminClient).block("alice", "admin");
        verify(identityAdminClient, never()).delete(anyString());
        verify(shopAdminClient, never()).purgeOrders(anyString());
    }

    @Test
    void anEligibleUserIsBlockedFirstThenPurgedThenDeleted() {
        givenUsers(user("alice", true, "ROLE_USER"), user("admin", true, "ROLE_ADMIN"));
        givenBlockSucceeds("alice");
        when(shopAdminClient.purgeEligibility("alice"))
                .thenReturn(Mono.just(new PurgeEligibility(true, List.of(1L, 2L), 0)));
        when(shopAdminClient.purgeOrders("alice"))
                .thenReturn(Mono.just(new PurgeResult(List.of(1L, 2L))));
        when(identityAdminClient.delete("alice")).thenReturn(Mono.empty());

        StepVerifier.create(service.delete("alice", "admin"))
                .assertNext(result -> {
                    assertThat(result.outcome()).isEqualTo(DeleteUserResult.DONE);
                    assertThat(result.deletedOrderCount()).isEqualTo(2);
                })
                .verifyComplete();

        var inOrder = org.mockito.Mockito.inOrder(identityAdminClient, shopAdminClient);
        inOrder.verify(identityAdminClient).block("alice", "admin");
        inOrder.verify(shopAdminClient).purgeEligibility("alice");
        inOrder.verify(shopAdminClient).purgeOrders("alice");
        inOrder.verify(identityAdminClient).delete("alice");
    }

    @Test
    void hardDeleteAlsoRemovesTheProfileRowAndAddresses() {
        UserProfile profile = new UserProfile("alice", "alice@example.com", "Alice", "A");
        givenUsers(user("alice", true, "ROLE_USER"), user("admin", true, "ROLE_ADMIN"));
        givenBlockSucceeds("alice");
        when(shopAdminClient.purgeEligibility("alice"))
                .thenReturn(Mono.just(new PurgeEligibility(true, List.of(), 0)));
        when(shopAdminClient.purgeOrders("alice")).thenReturn(Mono.just(new PurgeResult(List.of())));
        when(identityAdminClient.delete("alice")).thenReturn(Mono.empty());
        when(userProfileRepository.findByUsername("alice")).thenReturn(Mono.just(profile));
        when(userProfileRepository.delete(profile)).thenReturn(Mono.empty());

        StepVerifier.create(service.delete("alice", "admin")).expectNextCount(1).verifyComplete();

        verify(userProfileRepository).delete(profile);
    }

    // If auth-server refuses the delete, profile must not have already thrown
    // away the row that describes who the surviving user is.
    @Test
    void profileDataSurvivesAFailedIdentityDelete() {
        givenUsers(user("alice", true, "ROLE_USER"), user("admin", true, "ROLE_ADMIN"));
        givenBlockSucceeds("alice");
        when(shopAdminClient.purgeEligibility("alice"))
                .thenReturn(Mono.just(new PurgeEligibility(true, List.of(), 0)));
        when(shopAdminClient.purgeOrders("alice")).thenReturn(Mono.just(new PurgeResult(List.of())));
        when(identityAdminClient.delete("alice"))
                .thenReturn(Mono.error(new IllegalStateException("auth-server down")));

        StepVerifier.create(service.delete("alice", "admin"))
                .expectError(IllegalStateException.class)
                .verify();

        verify(userProfileRepository, never()).delete(any(UserProfile.class));
    }

    // ── Audit trail (D6) ────────────────────────────────────────────

    @Test
    void blockedInsteadIsRecordedWithTheOrderCount() {
        givenUsers(user("alice", true, "ROLE_USER"), user("admin", true, "ROLE_ADMIN"));
        givenBlockSucceeds("alice");
        when(shopAdminClient.purgeEligibility("alice"))
                .thenReturn(Mono.just(new PurgeEligibility(false, List.of(), 9)));

        StepVerifier.create(service.delete("alice", "admin")).expectNextCount(1).verifyComplete();

        ArgumentCaptor<AdminAction> captor = ArgumentCaptor.forClass(AdminAction.class);
        verify(adminActionRepository).save(captor.capture());
        AdminAction action = captor.getValue();
        assertThat(action.getActor()).isEqualTo("admin");
        assertThat(action.getAction()).isEqualTo("DELETE");
        assertThat(action.getTargetUser()).isEqualTo("alice");
        assertThat(action.getOutcome()).isEqualTo(DeleteUserResult.BLOCKED_INSTEAD);
        assertThat(action.getOrderCount()).isEqualTo(9);
        assertThat(action.getReason()).contains("blocked instead of deleted");
    }

    // A refused action is still an action an admin attempted.
    @Test
    void aRefusedActionIsRecordedAsFailed() {
        StepVerifier.create(service.block("admin", "admin"))
                .expectError(ResponseStatusException.class)
                .verify();

        ArgumentCaptor<AdminAction> captor = ArgumentCaptor.forClass(AdminAction.class);
        verify(adminActionRepository).save(captor.capture());
        assertThat(captor.getValue().getOutcome()).isEqualTo("FAILED");
    }

    // ── The list (D3, §2.1) ─────────────────────────────────────────

    @Test
    void theListIsBuiltFromAuthUsersNotProfiles() {
        givenUsers(user("alice", true, "ROLE_USER"), user("noprofile", true, "ROLE_USER"));
        UserProfile aliceProfile = new UserProfile("alice", "alice@example.com", "Alice", "A");
        UserProfile orphan = new UserProfile("102919241495532217479", "g@example.com", "G", "S");
        when(userProfileRepository.findAll()).thenReturn(Flux.just(aliceProfile, orphan));

        StepVerifier.create(service.listUsers().collectList())
                .assertNext(views -> {
                    // The Google-sub profile row is not a user and must not appear.
                    assertThat(views).extracting("username")
                            .containsExactly("alice", "noprofile");
                    assertThat(views.get(0).hasProfile()).isTrue();
                    // A real user with no profile row must still be listed.
                    assertThat(views.get(1).hasProfile()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void signInStateDistinguishesLinkedFromGoogle() {
        AuthUser local = new AuthUser(1L, "a", "a@x", null, null, true, "LOCAL", null,
                List.of(), null, null, null);
        AuthUser linked = new AuthUser(2L, "b", "b@x", null, null, true, "LOCAL", "google-sub",
                List.of(), null, null, null);
        AuthUser google = new AuthUser(3L, "c", "c@x", null, null, true, "GOOGLE", "google-sub",
                List.of(), null, null, null);

        assertThat(local.signInState()).isEqualTo("LOCAL");
        assertThat(linked.signInState()).isEqualTo("LINKED");
        assertThat(google.signInState()).isEqualTo("GOOGLE");
    }
}
