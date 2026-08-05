package org.granitesecurity.balance.route;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.granitesecurity.balance.dto.BalanceResponse;
import org.granitesecurity.balance.dto.GiftRequest;
import org.granitesecurity.balance.dto.ReconcileReport;
import org.granitesecurity.balance.dto.TransactionResponse;
import org.granitesecurity.balance.handler.AdminBalanceHandler;
import org.granitesecurity.balance.handler.BalanceHandler;
import org.springdoc.core.annotations.RouterOperation;
import org.springdoc.core.annotations.RouterOperations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Functional routes are invisible to springdoc on their own — the
 * {@code @RouterOperation} annotations below are the only reason Swagger UI
 * shows anything. Adding a route without one leaves it undocumented but live.
 */
@Configuration
public class BalanceRoute {

    @Bean
    @RouterOperations({
            @RouterOperation(
                    path = "/api/balance/me",
                    method = RequestMethod.GET,
                    beanClass = BalanceHandler.class,
                    beanMethod = "getMyBalance",
                    operation = @Operation(
                            operationId = "getMyBalance",
                            summary = "My balance",
                            description = "The caller's own balance, in rappen and CHF. "
                                    + "The account is created on first read. May be negative "
                                    + "when credit has been extended.",
                            security = @SecurityRequirement(name = "bearer-jwt"),
                            responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(
                                    responseCode = "200",
                                    content = @Content(schema = @Schema(implementation = BalanceResponse.class))))),
            @RouterOperation(
                    path = "/api/balance/me/transactions",
                    method = RequestMethod.GET,
                    beanClass = BalanceHandler.class,
                    beanMethod = "getMyTransactions",
                    operation = @Operation(
                            operationId = "getMyTransactions",
                            summary = "My ledger entries",
                            description = "Newest first. Signed amounts: negative means money left.",
                            security = @SecurityRequirement(name = "bearer-jwt"),
                            parameters = {
                                    @io.swagger.v3.oas.annotations.Parameter(name = "page", description = "0-based"),
                                    @io.swagger.v3.oas.annotations.Parameter(name = "size", description = "max 100")
                            },
                            responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(
                                    responseCode = "200",
                                    content = @Content(array = @io.swagger.v3.oas.annotations.media.ArraySchema(
                                            schema = @Schema(implementation = TransactionResponse.class)))))),
            @RouterOperation(
                    path = "/api/balance/admin/gifts",
                    method = RequestMethod.POST,
                    beanClass = AdminBalanceHandler.class,
                    beanMethod = "gift",
                    operation = @Operation(
                            operationId = "gift",
                            summary = "Gift credit to a user (ROLE_ADMIN)",
                            description = "Unbacked issuance: creates credit from nothing, "
                                    + "moving it from house:gift to the user. The acting admin "
                                    + "is taken from the JWT and recorded on the ledger entry.",
                            security = @SecurityRequirement(name = "bearer-jwt"),
                            requestBody = @RequestBody(required = true, content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(implementation = GiftRequest.class))))),
            @RouterOperation(
                    path = "/api/balance/admin/reconcile",
                    method = RequestMethod.GET,
                    beanClass = AdminBalanceHandler.class,
                    beanMethod = "reconcile",
                    operation = @Operation(
                            operationId = "reconcile",
                            summary = "Prove the ledger (ROLE_ADMIN)",
                            description = "Checks that every entry sums to zero, that no account "
                                    + "has drifted from its own entries, and that user balances "
                                    + "mirror house balances. Also reports backed vs unbacked "
                                    + "issuance and credit outstanding. `balanced` must be true.",
                            security = @SecurityRequirement(name = "bearer-jwt"),
                            responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(
                                    responseCode = "200",
                                    content = @Content(schema = @Schema(implementation = ReconcileReport.class)))))
    })
    public RouterFunction<ServerResponse> balanceRoutes(BalanceHandler balanceHandler,
                                                        AdminBalanceHandler adminHandler) {
        return RouterFunctions.route()
                .GET("/api/balance/me", balanceHandler::getMyBalance)
                .GET("/api/balance/me/transactions", balanceHandler::getMyTransactions)
                // Admin routes are gated in BalanceSec by path prefix, so they must
                // stay under /admin/ — a gift endpoint anywhere else would be
                // authenticated but not role-checked.
                .POST("/api/balance/admin/gifts", adminHandler::gift)
                .GET("/api/balance/admin/reconcile", adminHandler::reconcile)
                .build();
    }
}
