package org.granitesecurity.balance.handler;

import org.granitesecurity.balance.domain.BalanceIntent;
import org.granitesecurity.balance.dto.CreateIntentRequest;
import org.granitesecurity.balance.dto.IntentResponse;
import org.granitesecurity.balance.service.IntentService;
import org.granitesecurity.balance.service.Money;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Service-to-service only, behind SCOPE_internal. These four operations exist
 * because they are exactly what the PaymentProvider SPI needs: createIntent,
 * finalizePayment, retrieveIntent, createRefund.
 *
 * <p>A user token must never reach these — that would let anyone spend anyone
 * else's balance (docs/finance/finance.md §7.2).
 */
@Service
public class InternalIntentHandler {

    private final IntentService intentService;

    public InternalIntentHandler(IntentService intentService) {
        this.intentService = intentService;
    }

    public Mono<ServerResponse> create(ServerRequest request) {
        return request.bodyToMono(CreateIntentRequest.class)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "An intent is required")))
                .flatMap(req -> intentService.create(req.username(), req.amountMinor(), req.orderId()))
                .map(InternalIntentHandler::toResponse)
                .flatMap(body -> ServerResponse.status(HttpStatus.CREATED).bodyValue(body));
    }

    public Mono<ServerResponse> capture(ServerRequest request) {
        return intentService.capture(id(request))
                .map(InternalIntentHandler::toResponse)
                .flatMap(body -> ServerResponse.ok().bodyValue(body));
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        return intentService.get(id(request))
                .map(InternalIntentHandler::toResponse)
                .flatMap(body -> ServerResponse.ok().bodyValue(body));
    }

    public Mono<ServerResponse> refund(ServerRequest request) {
        return intentService.refund(id(request))
                .map(InternalIntentHandler::toResponse)
                .flatMap(body -> ServerResponse.ok().bodyValue(body));
    }

    private static UUID id(ServerRequest request) {
        try {
            return UUID.fromString(request.pathVariable("id"));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not an intent id");
        }
    }

    static IntentResponse toResponse(BalanceIntent intent) {
        return new IntentResponse(
                intent.getId().toString(),
                intent.getUsername(),
                intent.getAmountMinor(),
                Money.toChf(intent.getAmountMinor()),
                intent.getOrderId(),
                intent.getStatus(),
                intent.getTransferId() == null ? null : intent.getTransferId().toString(),
                intent.getDeclineReason());
    }
}
