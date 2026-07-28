package org.granitesecurity.profile.service;

import org.granitesecurity.profile.client.DeliveryAdminClient;
import org.granitesecurity.profile.client.IdentityAdminClient;
import org.granitesecurity.profile.client.PaymentAdminClient;
import org.granitesecurity.profile.client.ShopAdminClient;
import org.granitesecurity.profile.dto.AuthUser;
import org.granitesecurity.profile.dto.OrphanReport;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only reconciliation across the four databases a hard delete touches
 * (docs/users/blocking-users.md §8 Phase 6).
 *
 * <p>Nothing here deletes anything. A half-completed cascade — shop purged but
 * the OrdersPurged event never consumed, say — otherwise leaves no trace at
 * all, and this is what surfaces it.
 */
@Service
public class OrphanSweepService {

    private final IdentityAdminClient identityAdminClient;
    private final ShopAdminClient shopAdminClient;
    private final PaymentAdminClient paymentAdminClient;
    private final DeliveryAdminClient deliveryAdminClient;

    public OrphanSweepService(IdentityAdminClient identityAdminClient,
                              ShopAdminClient shopAdminClient,
                              PaymentAdminClient paymentAdminClient,
                              DeliveryAdminClient deliveryAdminClient) {
        this.identityAdminClient = identityAdminClient;
        this.shopAdminClient = shopAdminClient;
        this.paymentAdminClient = paymentAdminClient;
        this.deliveryAdminClient = deliveryAdminClient;
    }

    public Mono<OrphanReport> sweep() {
        Mono<Set<String>> usernames = identityAdminClient.listUsers()
                .map(AuthUser::username)
                .collect(Collectors.toSet());

        return Mono.zip(usernames, shopAdminClient.orderOwners())
                .flatMap(tuple -> {
                    Set<String> knownUsers = tuple.getT1();
                    List<OrphanReport.OrphanedOrders> orphanedOrders = tuple.getT2().stream()
                            .filter(owner -> !knownUsers.contains(owner.username()))
                            .map(owner -> new OrphanReport.OrphanedOrders(
                                    owner.username(), owner.orderCount()))
                            .toList();

                    // payment and delivery report the order ids they hold; only
                    // shop can say which of those no longer exist.
                    Mono<List<Long>> orphanedPayments = paymentAdminClient.orderIds()
                            .flatMap(shopAdminClient::unknownOrderIds);
                    Mono<List<Long>> orphanedDeliveries = deliveryAdminClient.orderIds()
                            .flatMap(shopAdminClient::unknownOrderIds);

                    return Mono.zip(orphanedPayments, orphanedDeliveries)
                            .map(orphans -> new OrphanReport(
                                    orphanedOrders, orphans.getT1(), orphans.getT2()));
                });
    }
}
