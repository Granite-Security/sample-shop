package org.granitesecurity.accounting.handler;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.granitesecurity.accounting.domain.Journal;
import org.granitesecurity.accounting.dto.ManualJournalRequests.Expense;
import org.granitesecurity.accounting.dto.ManualJournalRequests.Purchase;
import org.granitesecurity.accounting.dto.ManualJournalRequests.RawJournal;
import org.granitesecurity.accounting.dto.ManualJournalRequests.Reimbursement;
import org.granitesecurity.accounting.service.ManualJournalService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * The four forms of §15, and the reversal that is the only way to change anything already
 * posted (§15.2).
 *
 * <p>ADMIN or MANAGER. The acting user comes from the JWT and is recorded on the entry; it
 * is never in the request body.
 */
@Service
public class ManualJournalHandler {

    private final ManualJournalService manualJournalService;

    public ManualJournalHandler(ManualJournalService manualJournalService) {
        this.manualJournalService = manualJournalService;
    }

    @Operation(operationId = "postPurchase", summary = "Buy stock, equipment or a service",
            description = """
                    Debits what is being acquired and credits the bank — or accounts payable when
                    `onCredit` is set, because buying on credit and paying for something are
                    different facts rather than a presentation choice.""")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Posted"),
            @ApiResponse(responseCode = "400", description = "Unknown account, or a non-positive amount", content = @Content()),
            @ApiResponse(responseCode = "409", description = "Key already used, or the period is closed", content = @Content())
    })
    public Mono<ServerResponse> postPurchase(ServerRequest request) {
        return handle(request, Purchase.class, manualJournalService::purchase);
    }

    @Operation(operationId = "postExpense", summary = "Record an operating cost",
            description = """
                    Defaults to 6900. Set `incurredBy` when someone paid for it personally: the
                    credit then goes to 2600 as a payable to that person rather than to the bank,
                    with their name on the line, so "what do we owe Ana?" is a GROUP BY rather than
                    a chart-of-accounts migration every time someone joins.""")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Posted"),
            @ApiResponse(responseCode = "400", description = "Unknown account, or a non-positive amount", content = @Content())
    })
    public Mono<ServerResponse> postExpense(ServerRequest request) {
        return handle(request, Expense.class, manualJournalService::expense);
    }

    @Operation(operationId = "postReimbursement", summary = "Pay a member of staff back",
            description = """
                    Clears the payable to that person and pays it out of the bank.

                    **The bank here is a book account.** Reimbursing someone by crediting their
                    platform balance would be a third door into the ledger, and there are exactly
                    two. If that is ever wanted it is a balance feature with its own house account
                    and its own reconcile line, decided there — not a side effect of this.""")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Posted"),
            @ApiResponse(responseCode = "400", description = "No party, or a non-positive amount", content = @Content())
    })
    public Mono<ServerResponse> postReimbursement(ServerRequest request) {
        return handle(request, Reimbursement.class, manualJournalService::reimbursement);
    }

    @Operation(operationId = "postJournal", summary = "A raw balanced journal",
            description = """
                    The escape hatch, for what the three typed forms do not cover. Debits must equal
                    credits and each line must carry exactly one side — a line with both is two
                    lines somebody did not write.

                    Amounts are magnitudes: a negative is rejected rather than silently flipped.""")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Posted"),
            @ApiResponse(responseCode = "400", description = "Unbalanced, or a line with both or neither side", content = @Content())
    })
    public Mono<ServerResponse> postJournal(ServerRequest request) {
        return handle(request, RawJournal.class, manualJournalService::raw);
    }

    @Operation(operationId = "reverseJournal", summary = "Correct an entry by reversing it",
            description = """
                    The only way to change anything already posted, for anyone. There is no endpoint
                    that edits a journal and the database refuses the UPDATE, so a correction is a
                    new entry with every line flipped, pointing at what it reverses.

                    Dated today, not on the original's date: the reversal is a thing that happened
                    now, and backdating it into the period being corrected is how a closed month
                    quietly changes.""")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Reversed"),
            @ApiResponse(responseCode = "404", description = "No such journal", content = @Content())
    })
    public Mono<ServerResponse> reverseJournal(ServerRequest request) {
        UUID id = uuid(request.pathVariable("id"));
        String reason = request.queryParam("reason").orElse(null);
        return actor(request)
                .flatMap(who -> manualJournalService.reverse(id, reason, who))
                .flatMap(ManualJournalHandler::created);
    }

    private <T> Mono<ServerResponse> handle(ServerRequest request, Class<T> type,
                                            BiFunction<T, String, Mono<Journal>> post) {
        return actor(request).flatMap(who -> request.bodyToMono(type)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "A request body is required")))
                .flatMap(body -> post.apply(body, who)))
                .flatMap(ManualJournalHandler::created);
    }

    private static Mono<String> actor(ServerRequest request) {
        return request.principal()
                .cast(Authentication.class)
                .map(auth -> ((Jwt) auth.getCredentials()).getSubject());
    }

    private static Mono<ServerResponse> created(Journal journal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", journal.getId());
        body.put("period", journal.getPeriodCode());
        body.put("occurredAt", journal.getOccurredAt());
        body.put("postedAt", journal.getPostedAt());
        body.put("eventType", journal.getEventType());
        body.put("memo", journal.getMemo());
        body.put("createdBy", journal.getCreatedBy());
        body.put("reversesId", journal.getReversesId());
        return ServerResponse.status(HttpStatus.CREATED).bodyValue(body);
    }

    private static UUID uuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a journal id: " + raw);
        }
    }
}
