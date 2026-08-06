package org.granitesecurity.balance.handler;

import org.granitesecurity.balance.domain.Account;
import org.granitesecurity.balance.domain.LedgerEntry;
import org.granitesecurity.balance.dto.GiftRequest;
import org.granitesecurity.balance.dto.AccountView;
import org.granitesecurity.balance.dto.GiftResponse;
import org.granitesecurity.balance.dto.LedgerEntryView;
import org.granitesecurity.balance.repository.AccountRepository;
import org.granitesecurity.balance.repository.LedgerEntryRepository;
import org.granitesecurity.balance.service.BalanceService;
import org.granitesecurity.balance.service.IdempotencyService;
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
    private final IdempotencyService idempotencyService;
    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public AdminBalanceHandler(BalanceService balanceService,
                               ReconcileService reconcileService,
                               IdempotencyService idempotencyService,
                               AccountRepository accountRepository,
                               LedgerEntryRepository ledgerEntryRepository) {
        this.balanceService = balanceService;
        this.reconcileService = reconcileService;
        this.idempotencyService = idempotencyService;
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    /** Every account, house and user, with its balance. */
    public Mono<ServerResponse> listAccounts(ServerRequest request) {
        return accountRepository.findAllForTreasury()
                .map(a -> new AccountView(a.getId(), a.getUsername(), a.getKind(),
                        a.getBalanceMinor(), Money.toChf(a.getBalanceMinor())))
                .collectList()
                .flatMap(body -> ServerResponse.ok().bodyValue(body));
    }

    /**
     * Every movement, newest first. Entries come back with account_id rather than
     * a username: the account list is small and the caller already has it, so this
     * avoids a join and a projection for no gain.
     */
    public Mono<ServerResponse> ledger(ServerRequest request) {
        int size = Math.min(Math.max(intParam(request, "size", 50), 1), 200);
        long offset = (long) Math.max(intParam(request, "page", 0), 0) * size;
        return ledgerEntryRepository.findAllNewestFirst(size, offset)
                .map(e -> new LedgerEntryView(
                        e.getId(),
                        e.getTransferId() == null ? null : e.getTransferId().toString(),
                        e.getAccountId(),
                        e.getAmountMinor(),
                        Money.toChf(e.getAmountMinor()),
                        e.getKind(),
                        e.getReference(),
                        e.getMemo(),
                        e.getCreatedAt()))
                .collectList()
                .flatMap(body -> ServerResponse.ok().bodyValue(body));
    }

    private static int intParam(ServerRequest request, String name, int fallback) {
        try {
            return request.queryParam(name).map(Integer::parseInt).orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
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
                    String recipient = gift.username().trim();

                    // Unbacked issuance: house:gift goes further negative, and that
                    // number is the platform's total conjured credit (§2).
                    Mono<GiftResponse> issue = balanceService.move(
                                    Account.HOUSE_GIFT,
                                    recipient,
                                    amountMinor,
                                    LedgerEntry.KIND_GIFT,
                                    admin,
                                    gift.reason())
                            .doOnSuccess(id -> log.info(
                                    "Admin {} gifted {} rappen to {} ({})",
                                    admin, amountMinor, recipient, id))
                            .map(id -> new GiftResponse(id.toString(), recipient, amountMinor,
                                    Money.toChf(amountMinor), admin));

                    return idempotencyService.once(gift.idempotencyKey(), GiftResponse.class, issue)
                            .flatMap(body -> ServerResponse.status(HttpStatus.CREATED).bodyValue(body));
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
