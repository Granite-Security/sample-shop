package org.granitesecurity.balance.handler;

import org.granitesecurity.balance.domain.Account;
import org.granitesecurity.balance.domain.LedgerEntry;
import org.granitesecurity.balance.dto.BalanceResponse;
import org.granitesecurity.balance.dto.TransactionResponse;
import org.granitesecurity.balance.service.BalanceService;
import org.granitesecurity.balance.service.Money;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * The account holder's own view. The owner is always the JWT subject; no request
 * ever names whose balance to read.
 */
@Service
public class BalanceHandler {

    private final BalanceService balanceService;

    public BalanceHandler(BalanceService balanceService) {
        this.balanceService = balanceService;
    }

    public Mono<ServerResponse> getMyBalance(ServerRequest request) {
        return username(request)
                // Created on first read, like profile does — a user who has never
                // been given money still has an account showing zero.
                .flatMap(balanceService::findOrCreate)
                .map(BalanceHandler::toResponse)
                .flatMap(body -> ServerResponse.ok().bodyValue(body));
    }

    public Mono<ServerResponse> getMyTransactions(ServerRequest request) {
        int page = intParam(request, "page", 0);
        int size = intParam(request, "size", 20);
        return username(request)
                .flatMapMany(user -> balanceService.transactions(user, page, size))
                .map(BalanceHandler::toResponse)
                .collectList()
                .flatMap(body -> ServerResponse.ok().bodyValue(body));
    }

    static BalanceResponse toResponse(Account account) {
        return new BalanceResponse(
                account.getUsername(),
                account.getBalanceMinor(),
                Money.toChf(account.getBalanceMinor()),
                account.getCurrency());
    }

    static TransactionResponse toResponse(LedgerEntry entry) {
        return new TransactionResponse(
                entry.getId(),
                entry.getTransferId() == null ? null : entry.getTransferId().toString(),
                entry.getAmountMinor(),
                Money.toChf(entry.getAmountMinor()),
                entry.getKind(),
                entry.getReference(),
                entry.getMemo(),
                entry.getCreatedAt());
    }

    private static int intParam(ServerRequest request, String name, int fallback) {
        try {
            return request.queryParam(name).map(Integer::parseInt).orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private Mono<String> username(ServerRequest request) {
        return request.principal()
                .cast(Authentication.class)
                .map(auth -> ((Jwt) auth.getCredentials()).getSubject());
    }
}
