package org.granitesecurity.accounting.handler;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.granitesecurity.accounting.domain.ChartAccount;
import org.granitesecurity.accounting.repository.ChartAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * The chart of accounts, read-only (docs/finance/accounting.md §4.3).
 *
 * <p>The only endpoint the scaffold has, and it earns its place twice over: it is a
 * real part of §4.3, and it is the end-to-end proof that this service is wired up —
 * a 200 with 23 accounts means Liquibase ran, R2DBC is mapping, the resource server
 * is validating tokens and the gateway route reaches the pod. Journals, the trial
 * balance and the accrual reports land in steps 5 and 13.
 */
@Service
public class ChartHandler {

    private final ChartAccountRepository chartAccountRepository;

    public ChartHandler(ChartAccountRepository chartAccountRepository) {
        this.chartAccountRepository = chartAccountRepository;
    }

    @Operation(operationId = "getChartOfAccounts",
            summary = "The chart of accounts",
            description = """
                    Admin only. Seeded by migration and not editable: a new account is an
                    accounting-policy decision and belongs in a reviewed migration, not a form.""")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every account, in code order"),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN", content = @Content()),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content())
    })
    public Mono<ServerResponse> getChartOfAccounts(ServerRequest request) {
        return chartAccountRepository.findAllOrdered()
                .map(ChartHandler::toView)
                .collectList()
                .flatMap(accounts -> ServerResponse.ok().bodyValue(Map.of("accounts", accounts)));
    }

    private static AccountView toView(ChartAccount account) {
        return new AccountView(account.getCode(), account.getName(), account.getType(),
                account.getNormalSide(), account.isContra());
    }

    /** Deliberately not the entity: {@code createdAt} is when the row was seeded, which is nobody's business. */
    public record AccountView(String code, String name, String type, String normalSide, boolean contra) {}
}
