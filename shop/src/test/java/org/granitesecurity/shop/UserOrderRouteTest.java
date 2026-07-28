package org.granitesecurity.shop;

import org.granitesecurity.shop.dto.PagedResult;
import org.granitesecurity.shop.dto.PurgeEligibility;
import org.granitesecurity.shop.dto.PurgeResult;
import org.granitesecurity.shop.service.OrderService;
import org.granitesecurity.shop.service.UserOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@SpringBootTest
class UserOrderRouteTest extends AbstractTestcontainers {

    @Autowired
    private ApplicationContext applicationContext;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private UserOrderService userOrderService;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .build();
    }

    private static SimpleGrantedAuthority role(String role) {
        return new SimpleGrantedAuthority("ROLE_" + role);
    }

    private static SimpleGrantedAuthority scope(String scope) {
        return new SimpleGrantedAuthority("SCOPE_" + scope);
    }

    // ── GET /api/shop/users/{username}/orders — ROLE_ADMIN ──────────

    @Test
    void listingAnotherUsersOrdersRequiresAuthentication() {
        webTestClient.get().uri("/api/shop/users/alice/orders")
                .exchange()
                .expectStatus().isUnauthorized();

        verify(orderService, never()).getOrdersForUser(anyString(), anyInt(), anyInt());
    }

    // Without this, any logged-in customer could read every other customer's
    // order history just by knowing a username.
    @Test
    void aPlainUserCannotListAnotherUsersOrders() {
        webTestClient.mutateWith(mockJwt().authorities(role("USER")))
                .get().uri("/api/shop/users/alice/orders")
                .exchange()
                .expectStatus().isForbidden();

        verify(orderService, never()).getOrdersForUser(anyString(), anyInt(), anyInt());
    }

    @Test
    void anAdminCanListAnotherUsersOrders() {
        when(orderService.getOrdersForUser("alice", 0, 20))
                .thenReturn(Mono.just(new PagedResult<>(List.of(), 0L, 0, 20)));

        webTestClient.mutateWith(mockJwt().authorities(role("ADMIN")))
                .get().uri("/api/shop/users/alice/orders")
                .exchange()
                .expectStatus().isOk();

        verify(orderService).getOrdersForUser("alice", 0, 20);
    }

    // ── /api/shop/internal/** — SCOPE_internal only ─────────────────

    // ROLE_ADMIN is a *user* role; the internal surface is service-to-service.
    // An admin's browser token must not reach it.
    @Test
    void anAdminUserTokenCannotReachTheInternalSurface() {
        webTestClient.mutateWith(mockJwt().authorities(role("ADMIN")))
                .get().uri("/api/shop/internal/users/alice/purge-eligibility")
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(mockJwt().authorities(role("ADMIN")))
                .delete().uri("/api/shop/internal/users/alice/orders")
                .exchange()
                .expectStatus().isForbidden();

        verify(userOrderService, never()).purgeOrders(anyString());
    }

    @Test
    void internalEligibilityRequiresScopeInternal() {
        when(userOrderService.purgeEligibility("alice"))
                .thenReturn(Mono.just(new PurgeEligibility(true, List.of(1L), 0)));

        webTestClient.mutateWith(mockJwt().authorities(scope("internal")))
                .get().uri("/api/shop/internal/users/alice/purge-eligibility")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.eligible").isEqualTo(true)
                .jsonPath("$.paidOrderCount").isEqualTo(0);
    }

    @Test
    void internalPurgeRequiresScopeInternal() {
        when(userOrderService.purgeOrders("alice"))
                .thenReturn(Mono.just(new PurgeResult(List.of(1L, 2L))));

        webTestClient.mutateWith(mockJwt().authorities(scope("internal")))
                .delete().uri("/api/shop/internal/users/alice/orders")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.deletedOrderIds[0]").isEqualTo(1);
    }

    // "internal" sits where {username} would, so the internal rule has to be
    // matched first — otherwise this path would be an admin-role endpoint.
    @Test
    void theInternalPrefixIsNotTreatedAsAUsername() {
        webTestClient.get().uri("/api/shop/internal/users/alice/purge-eligibility")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
