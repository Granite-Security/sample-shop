package org.granitesecurity.shop.handler;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.granitesecurity.shop.service.RevenueService;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Read-only, admin-only (docs/finance/accounting.md D20). Adding a report must never add
 * a way to change money, so nothing under {@code /api/shop/admin/**} has a side effect.
 */
@Service
public class AdminRevenueHandler {

    private final RevenueService revenueService;

    public AdminRevenueHandler(RevenueService revenueService) {
        this.revenueService = revenueService;
    }

    @Operation(operationId = "getRevenue",
            summary = "Cash view of sales and refunds",
            description = """
                    Admin only. Buckets orders by when money moved: gross by the date the order
                    first reached PAID, refunds by the date they reached REIMBURSED. This is the
                    cash view, not recognised revenue — revenue is recognised on delivery and is
                    reported by the accounting service.""")
    @SecurityRequirement(name = "bearer-jwt")
    @Parameter(name = "granularity", description = "year, month or week", example = "month")
    @Parameter(name = "currency", description = "ISO code; series are never summed across currencies", example = "CHF")
    @Parameter(name = "from", description = "ISO date, inclusive", example = "2025-08-01")
    @Parameter(name = "to", description = "ISO date, exclusive", example = "2026-08-01")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One row per bucket, including empty ones"),
            @ApiResponse(responseCode = "400", description = "Unknown granularity, bad date or too wide a range", content = @Content()),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN or MANAGER", content = @Content()),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content())
    })
    public Mono<ServerResponse> getRevenue(ServerRequest request) {
        return revenueService.report(
                        request.queryParam("granularity").orElse(null),
                        request.queryParam("currency").orElse(null),
                        request.queryParam("from").orElse(null),
                        request.queryParam("to").orElse(null))
                .flatMap(report -> ServerResponse.ok().bodyValue(report));
    }

    @Operation(operationId = "getRevenueCurrencies",
            summary = "Currencies that have orders",
            description = """
                    Admin only. Lets the page show a currency selector only when more than one
                    currency has orders — shop priced in USD before the 2026-08-01 cutover and in
                    CHF after, and the two must never appear in one total.""")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Distinct currencies, ascending"),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN or MANAGER", content = @Content()),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content())
    })
    public Mono<ServerResponse> getRevenueCurrencies(ServerRequest request) {
        return revenueService.currencies()
                .flatMap(currencies -> ServerResponse.ok().bodyValue(Map.of("currencies", currencies)));
    }
}
