package org.granitesecurity.shop.handler;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.granitesecurity.shop.dto.PackagingQuoteRequest;
import org.granitesecurity.shop.service.PackagingService;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Service
public class PackagingHandler {

    private final PackagingService packagingService;

    public PackagingHandler(PackagingService packagingService) {
        this.packagingService = packagingService;
    }

    @Operation(operationId = "quotePackaging", summary = "Price the packaging for a cart",
            description = "Returns every compatible box option per packaging group, already priced "
                    + "for this cart. Read-only: nothing is stored and no stock is touched.")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Quote — packagingRequired is false when nothing needs a box"),
            @ApiResponse(responseCode = "400", description = "Malformed cart", content = @Content()),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content())
    })
    public Mono<ServerResponse> quote(ServerRequest request) {
        return request.bodyToMono(PackagingQuoteRequest.class)
                .defaultIfEmpty(new PackagingQuoteRequest(java.util.List.of()))
                .flatMap(packagingService::quote)
                .flatMap(quote -> ServerResponse.ok().bodyValue(quote));
    }
}
