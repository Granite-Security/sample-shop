package org.granitesecurity.balance.handler;

import org.granitesecurity.balance.domain.Account;
import org.granitesecurity.balance.domain.LedgerEntry;
import org.granitesecurity.balance.dto.GiftRequest;
import org.granitesecurity.balance.service.BalanceService;
import org.granitesecurity.balance.service.Money;
import org.granitesecurity.balance.service.ReconcileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Admin operations. Authorization is in BalanceSec, not here — but note that
 * gifting is money creation, so the ROLE_ADMIN check on /api/balance/admin/**
 * is load-bearing, not cosmetic (D11).
 */
@Service
public class AdminBalanceHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminBalanceHandler.class);

    private final BalanceService balanceService;
    private final ReconcileService reconcileService;

    public AdminBalanceHandler(BalanceService balanceService, ReconcileService reconcileService) {
        this.balanceService = balanceService;
        this.reconcileService = reconcileService;
    }

    public Mono<ServerResponse> gift(ServerRequest request) {
        return actor(request).flatMap(admin -> request.bodyToMono(GiftRequest.class)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "A gift is required")))
                .flatMap(gift -> {
                    if (gift.username() == null || gift.username().isBlank()) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "A recipient username is required"));
                    }
                    long amountMinor = Money.toRappen(gift.amountChf());
                    // Unbacked issuance: house:gift goes further negative, and that
                    // number is the platform's total conjured credit (§2).
                    return balanceService.move(
                                    Account.HOUSE_GIFT,
                                    gift.username().trim(),
                                    amountMinor,
                                    LedgerEntry.KIND_GIFT,
                                    admin,
                                    gift.reason())
                            .doOnSuccess(id -> log.info(
                                    "Admin {} gifted {} rappen to {} ({})",
                                    admin, amountMinor, gift.username(), id))
                            .flatMap(id -> ServerResponse.status(HttpStatus.CREATED)
                                    .bodyValue(Map.of(
                                            "transferId", id.toString(),
                                            "username", gift.username().trim(),
                                            "amountMinor", amountMinor)));
                }))
                // Money.toRappen rejects zero, negative and sub-rappen amounts; those
                // are bad requests, not server errors.
                .onErrorResume(IllegalArgumentException.class, e ->
                        ServerResponse.badRequest().bodyValue(Map.of("error", e.getMessage())));
    }

    public Mono<ServerResponse> reconcile(ServerRequest request) {
        return reconcileService.reconcile()
                .flatMap(report -> ServerResponse.ok().bodyValue(report));
    }

    private Mono<String> actor(ServerRequest request) {
        return request.principal()
                .cast(Authentication.class)
                .map(auth -> ((Jwt) auth.getCredentials()).getSubject());
    }
}
