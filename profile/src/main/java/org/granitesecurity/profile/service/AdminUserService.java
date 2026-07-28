package org.granitesecurity.profile.service;

import org.granitesecurity.profile.client.IdentityAdminClient;
import org.granitesecurity.profile.client.ShopAdminClient;
import org.granitesecurity.profile.domain.AdminAction;
import org.granitesecurity.profile.dto.AdminUserView;
import org.granitesecurity.profile.dto.AuthUser;
import org.granitesecurity.profile.dto.DeleteUserResult;
import org.granitesecurity.profile.dto.PurgeEligibility;
import org.granitesecurity.profile.domain.UserProfile;
import org.granitesecurity.profile.repository.AdminActionRepository;
import org.granitesecurity.profile.repository.DeliveryAddressRepository;
import org.granitesecurity.profile.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The orchestrator. profile owns the block/unblock/delete operation because the
 * admin page is served here and this is where ROLE_ADMIN is enforced;
 * auth-server only executes (docs/users/blocking-users.md §3, D4).
 */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    private static final String BLOCK = "BLOCK";
    private static final String UNBLOCK = "UNBLOCK";
    private static final String DELETE = "DELETE";
    private static final String FAILED = "FAILED";

    private final IdentityAdminClient identityAdminClient;
    private final ShopAdminClient shopAdminClient;
    private final UserProfileRepository userProfileRepository;
    private final DeliveryAddressRepository deliveryAddressRepository;
    private final AdminActionRepository adminActionRepository;
    private final TransactionalOperator transactionalOperator;

    public AdminUserService(IdentityAdminClient identityAdminClient,
                            ShopAdminClient shopAdminClient,
                            UserProfileRepository userProfileRepository,
                            DeliveryAddressRepository deliveryAddressRepository,
                            AdminActionRepository adminActionRepository,
                            TransactionalOperator transactionalOperator) {
        this.identityAdminClient = identityAdminClient;
        this.shopAdminClient = shopAdminClient;
        this.userProfileRepository = userProfileRepository;
        this.deliveryAddressRepository = deliveryAddressRepository;
        this.adminActionRepository = adminActionRepository;
        this.transactionalOperator = transactionalOperator;
    }

    /**
     * The admin list, built from auth-server users and enriched with profile
     * data — never from profiles, which are a different set (§2.1, D3).
     *
     * <p>Only the profile side is buffered (it is the lookup table of the
     * join); auth users stream through it and each view is emitted as its
     * user arrives.
     */
    public Flux<AdminUserView> listUsers() {
        return userProfileRepository.findAll()
                .collectMap(UserProfile::getUsername, Function.identity())
                .flatMapMany(profiles -> identityAdminClient.listUsers()
                        .map(user -> toView(user, profiles)));
    }

    private AdminUserView toView(AuthUser user, Map<String, UserProfile> profiles) {
        UserProfile profile = profiles.get(user.username());
        return new AdminUserView(
                user.username(),
                user.email(),
                user.firstName(),
                user.lastName(),
                profile != null ? profile.getDisplayName() : null,
                user.enabled(),
                user.signInState(),
                user.roles(),
                profile != null,
                user.blockedAt(),
                user.blockedBy(),
                profile != null ? profile.getCreatedAt() : null);
    }

    public Mono<AdminUserView> block(String username, String actor) {
        // Mono.defer, not .then(client.block(...)): the latter would call the
        // client during assembly, before the guard rails have had a chance to
        // reject. Nothing would be sent — WebClient's Mono is cold — but "we
        // never even asked" is the property worth keeping true.
        return guardRails(username, actor, BLOCK)
                .then(Mono.defer(() -> identityAdminClient.block(username, actor)))
                .flatMap(user -> record(actor, BLOCK, username, DeleteUserResult.DONE, null, null)
                        .thenReturn(toView(user, Map.of())))
                .onErrorResume(recordFailure(actor, BLOCK, username));
    }

    public Mono<AdminUserView> unblock(String username, String actor) {
        // No guard rails: unblocking cannot lock anyone out, and unblocking
        // yourself is impossible anyway — a blocked admin has no working token.
        return identityAdminClient.unblock(username)
                .flatMap(user -> record(actor, UNBLOCK, username, DeleteUserResult.DONE, null, null)
                        .thenReturn(toView(user, Map.of())))
                .onErrorResume(recordFailure(actor, UNBLOCK, username));
    }

    /**
     * Block first, then check, then delete.
     *
     * <p>The order matters. Blocking before the eligibility check shrinks the
     * window in which the user could place an order between the check and the
     * purge down to the access-token lifetime (§4.2, D5). If the check then says
     * they have paid orders, the block is the final state — that is the
     * "blocked instead of deleted" outcome, not a failure to clean up.
     */
    public Mono<DeleteUserResult> delete(String username, String actor) {
        return guardRails(username, actor, DELETE)
                .then(Mono.defer(() -> identityAdminClient.block(username, actor)))
                .then(Mono.defer(() -> shopAdminClient.purgeEligibility(username)))
                .flatMap(eligibility -> eligibility.eligible()
                        ? hardDelete(username, actor, eligibility)
                        : blockInstead(username, actor, eligibility))
                .onErrorResume(recordFailure(actor, DELETE, username));
    }

    private Mono<DeleteUserResult> hardDelete(String username, String actor, PurgeEligibility eligibility) {
        return shopAdminClient.purgeOrders(username)
                .flatMap(purge -> Mono.defer(() -> identityAdminClient.delete(username))
                        // profile's own rows go last: if anything above failed,
                        // the user still exists and their profile should still
                        // describe them.
                        .then(deleteProfileData(username))
                        .then(record(actor, DELETE, username, DeleteUserResult.DONE,
                                purge.deletedOrderIds().size(), null))
                        .thenReturn(DeleteUserResult.done(purge.deletedOrderIds().size())));
    }

    private Mono<DeleteUserResult> blockInstead(String username, String actor, PurgeEligibility eligibility) {
        String reason = "User has " + eligibility.paidOrderCount()
                + " order(s) whose payment moved money; blocked instead of deleted";
        log.info("{} blocked instead of deleted: {}", username, reason);
        return record(actor, DELETE, username, DeleteUserResult.BLOCKED_INSTEAD,
                eligibility.paidOrderCount(), reason)
                .thenReturn(DeleteUserResult.blockedInstead(eligibility.paidOrderCount()));
    }

    /**
     * Deliberately scoped to the profile row and its delivery addresses, per
     * §8 Phase 4. Note that {@code user_file} rows — and the objects they point
     * at in storage — are NOT removed here.
     */
    private Mono<Void> deleteProfileData(String username) {
        // One local transaction: both writes hit profile's own database, and a
        // crash between them would leave a half-scrubbed profile. This is the
        // only place a transaction applies — the wider delete cascade spans
        // three services' databases over HTTP and is a saga, not a transaction.
        return deliveryAddressRepository.findByUsername(username)
                .flatMap(deliveryAddressRepository::delete)
                .then(userProfileRepository.findByUsername(username)
                        .flatMap(userProfileRepository::delete))
                .then()
                .as(transactionalOperator::transactional);
    }

    // ── Guard rails (§7), enforced server-side ──────────────────────

    private Mono<Void> guardRails(String username, String actor, String action) {
        if (username.equals(actor)) {
            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                    "You cannot " + action.toLowerCase() + " your own account"));
        }
        return identityAdminClient.listUsers()
                .collectList()
                .flatMap(users -> {
                    AuthUser target = users.stream()
                            .filter(user -> user.username().equals(username))
                            .findFirst()
                            .orElse(null);
                    if (target == null) {
                        // §7: not a silent success.
                        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                "No such user: " + username));
                    }
                    // Without this, one click locks everyone out of the admin
                    // UI with no recovery short of SQL.
                    if (target.isAdmin() && isLastEnabledAdmin(users, username)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                "Cannot " + action.toLowerCase()
                                        + " the last enabled admin — promote another admin first"));
                    }
                    return Mono.empty();
                });
    }

    private boolean isLastEnabledAdmin(List<AuthUser> users, String username) {
        return users.stream()
                .filter(AuthUser::isAdmin)
                .filter(AuthUser::enabled)
                .filter(user -> !user.username().equals(username))
                .findAny()
                .isEmpty();
    }

    // ── Audit trail (D6) ────────────────────────────────────────────

    private Mono<AdminAction> record(String actor, String action, String target, String outcome,
                                     Integer orderCount, String reason) {
        return adminActionRepository.save(
                new AdminAction(actor, action, target, outcome, orderCount, reason));
    }

    /**
     * A refused or failed action is still an action an admin attempted, so it
     * lands in the trail as FAILED before the error is re-raised.
     */
    private <T> Function<Throwable, Mono<T>> recordFailure(String actor, String action, String target) {
        return error -> record(actor, action, target, FAILED, null, error.getMessage())
                .then(Mono.error(error));
    }
}
