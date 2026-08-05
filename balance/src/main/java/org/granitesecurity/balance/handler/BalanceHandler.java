package org.granitesecurity.balance.handler;

import org.granitesecurity.balance.client.ProfileClient;
import org.granitesecurity.balance.domain.Account;
import org.granitesecurity.balance.domain.LedgerEntry;
import org.granitesecurity.balance.dto.BalanceResponse;
import org.granitesecurity.balance.dto.TransactionResponse;
import org.granitesecurity.balance.dto.TransferRequest;
import org.granitesecurity.balance.dto.TransferResponse;
import org.granitesecurity.balance.service.BalanceService;
import org.granitesecurity.balance.service.IdempotencyService;
import org.granitesecurity.balance.service.Money;
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
 * The account holder's own view. The owner is always the JWT subject; no request
 * ever names whose balance to read.
 */
@Service
public class BalanceHandler {

    private final BalanceService balanceService;
    private final ProfileClient profileClient;
    private final IdempotencyService idempotencyService;

    public BalanceHandler(BalanceService balanceService,
                          ProfileClient profileClient,
                          IdempotencyService idempotencyService) {
        this.balanceService = balanceService;
        this.profileClient = profileClient;
        this.idempotencyService = idempotencyService;
    }

    /**
     * Send money to another user. Insufficient funds is a 402, not a 500: the
     * request was well-formed and was declined by the credit policy.
     */
    public Mono<ServerResponse> transfer(ServerRequest request) {
        return username(request).flatMap(sender -> request.bodyToMono(TransferRequest.class)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "A transfer is required")))
                .flatMap(req -> {
                    if (req.to() == null || req.to().isBlank()) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "A recipient is required"));
                    }
                    String recipient = req.to().trim();
                    if (recipient.equals(sender)) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "You cannot send money to yourself"));
                    }
                    long amountMinor = Money.toRappen(req.amountChf());

                    Mono<TransferResponse> movement = profileClient.requireUser(recipient)
                            .flatMap(validated -> balanceService.move(
                                    sender, validated, amountMinor,
                                    LedgerEntry.KIND_TRANSFER, sender, req.memo()))
                            .map(id -> new TransferResponse(
                                    id.toString(), sender, recipient, amountMinor,
                                    Money.toChf(amountMinor)));

                    return idempotencyService.once(
                            req.idempotencyKey(), TransferResponse.class, movement);
                })
                .flatMap(body -> ServerResponse.status(HttpStatus.CREATED).bodyValue(body)))
                .onErrorResume(BalanceService.InsufficientFundsException.class, e ->
                        ServerResponse.status(HttpStatus.PAYMENT_REQUIRED)
                                .bodyValue(Map.of("error", "Declined: insufficient balance")))
                .onErrorResume(IllegalArgumentException.class, e ->
                        ServerResponse.badRequest().bodyValue(Map.of("error", e.getMessage())));
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
