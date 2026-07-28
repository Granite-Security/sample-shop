package org.granitesecurity.profile.service;

import org.granitesecurity.profile.client.DeliveryAdminClient;
import org.granitesecurity.profile.client.IdentityAdminClient;
import org.granitesecurity.profile.client.PaymentAdminClient;
import org.granitesecurity.profile.client.ShopAdminClient;
import org.granitesecurity.profile.dto.AuthUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrphanSweepServiceTest {

    private IdentityAdminClient identityAdminClient;
    private ShopAdminClient shopAdminClient;
    private PaymentAdminClient paymentAdminClient;
    private DeliveryAdminClient deliveryAdminClient;
    private OrphanSweepService service;

    @BeforeEach
    void setUp() {
        identityAdminClient = mock(IdentityAdminClient.class);
        shopAdminClient = mock(ShopAdminClient.class);
        paymentAdminClient = mock(PaymentAdminClient.class);
        deliveryAdminClient = mock(DeliveryAdminClient.class);
        service = new OrphanSweepService(identityAdminClient, shopAdminClient,
                paymentAdminClient, deliveryAdminClient);

        when(paymentAdminClient.orderIds()).thenReturn(Mono.just(List.of()));
        when(deliveryAdminClient.orderIds()).thenReturn(Mono.just(List.of()));
        when(shopAdminClient.unknownOrderIds(anyList())).thenReturn(Mono.just(List.of()));
    }

    private AuthUser user(String username) {
        return new AuthUser(1L, username, username + "@example.com", null, null,
                true, "LOCAL", null, List.of(), null, null, null);
    }

    private void givenUsers(String... usernames) {
        when(identityAdminClient.listUsers())
                .thenReturn(Flux.fromArray(usernames).map(this::user));
    }

    @Test
    void reportsNothingWhenEverythingReconciles() {
        givenUsers("alice", "bob");
        when(shopAdminClient.orderOwners()).thenReturn(Mono.just(List.of(
                new ShopAdminClient.OrderOwner("alice", 3))));

        StepVerifier.create(service.sweep())
                .assertNext(report -> assertThat(report.isClean()).isTrue())
                .verifyComplete();
    }

    // The trace a half-completed cascade leaves: shop still holds orders for a
    // username auth-server no longer knows.
    @Test
    void findsOrdersBelongingToAUserThatNoLongerExists() {
        givenUsers("alice");
        when(shopAdminClient.orderOwners()).thenReturn(Mono.just(List.of(
                new ShopAdminClient.OrderOwner("alice", 3),
                new ShopAdminClient.OrderOwner("deleted-user", 2))));

        StepVerifier.create(service.sweep())
                .assertNext(report -> {
                    assertThat(report.isClean()).isFalse();
                    assertThat(report.orphanedOrders()).hasSize(1);
                    assertThat(report.orphanedOrders().get(0).username()).isEqualTo("deleted-user");
                    assertThat(report.orphanedOrders().get(0).orderCount()).isEqualTo(2);
                })
                .verifyComplete();
    }

    // The other half: the OrdersPurged event never reached payment/delivery, so
    // their rows outlived the orders they point at.
    @Test
    void findsPaymentAndDeliveryRowsWhoseOrderIsGone() {
        givenUsers("alice");
        when(shopAdminClient.orderOwners()).thenReturn(Mono.just(List.of()));
        when(paymentAdminClient.orderIds()).thenReturn(Mono.just(List.of(1L, 2L)));
        when(deliveryAdminClient.orderIds()).thenReturn(Mono.just(List.of(2L, 3L)));
        when(shopAdminClient.unknownOrderIds(List.of(1L, 2L))).thenReturn(Mono.just(List.of(2L)));
        when(shopAdminClient.unknownOrderIds(List.of(2L, 3L))).thenReturn(Mono.just(List.of(2L, 3L)));

        StepVerifier.create(service.sweep())
                .assertNext(report -> {
                    assertThat(report.orphanedPaymentOrderIds()).containsExactly(2L);
                    assertThat(report.orphanedDeliveryOrderIds()).containsExactly(2L, 3L);
                    assertThat(report.isClean()).isFalse();
                })
                .verifyComplete();
    }

    // A blocked user still exists, so their orders are not orphans.
    @Test
    void ordersOfAnExistingUserAreNeverReported() {
        givenUsers("blocked-but-present");
        when(shopAdminClient.orderOwners()).thenReturn(Mono.just(List.of(
                new ShopAdminClient.OrderOwner("blocked-but-present", 5))));

        StepVerifier.create(service.sweep())
                .assertNext(report -> assertThat(report.orphanedOrders()).isEmpty())
                .verifyComplete();
    }
}
