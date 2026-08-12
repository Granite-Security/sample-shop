package org.granitesecurity.shop.handler;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.granitesecurity.shop.dto.VoucherPreviewRequest;
import org.granitesecurity.shop.service.VoucherService;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Service
public class VoucherHandler {

    private final VoucherService voucherService;

    public VoucherHandler(VoucherService voucherService) {
        this.voucherService = voucherService;
    }

    @Operation(operationId = "previewVoucher", summary = "Price a voucher code against a cart",
            description = "Returns what the code is worth on this cart, or why it is worth nothing. "
                    + "Read-only: nothing is stored and the code is not reserved — placement "
                    + "validates it again, and it can expire in between.")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Priced. A refused code is valid=false "
                    + "with a reason, not an error status — checkout has to render why"),
            @ApiResponse(responseCode = "400", description = "Malformed cart or missing code", content = @Content()),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content())
    })
    public Mono<ServerResponse> preview(ServerRequest request) {
        return request.bodyToMono(VoucherPreviewRequest.class)
                .zipWith(getUsername(request))
                .flatMap(tuple -> voucherService.preview(tuple.getT1(), tuple.getT2()))
                .flatMap(response -> ServerResponse.ok().bodyValue(response));
    }

    private Mono<String> getUsername(ServerRequest request) {
        return request.principal()
                .cast(Authentication.class)
                .map(auth -> ((Jwt) auth.getCredentials()).getSubject());
    }
}
