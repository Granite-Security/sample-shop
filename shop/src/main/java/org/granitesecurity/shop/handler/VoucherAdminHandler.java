package org.granitesecurity.shop.handler;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.granitesecurity.shop.dto.CreateVoucherRequest;
import org.granitesecurity.shop.dto.VoucherResponse;
import org.granitesecurity.shop.service.VoucherAdminService;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Voucher maintenance. Guarded in {@code ShopSec} — ADMIN and MANAGER throughout,
 * including create and revoke: running a discount campaign is the manager's job, and
 * a manager who can already refund an order in full can already give money away.
 *
 * <p>Note this is <em>looser</em> than packaging maintenance, which stays ADMIN-only.
 * Retiring a box takes a group of products off sale; a voucher only discounts one,
 * revoking it is instant, and a placed order keeps what it was charged.
 */
@Service
public class VoucherAdminHandler {

    private final VoucherAdminService voucherAdminService;

    public VoucherAdminHandler(VoucherAdminService voucherAdminService) {
        this.voucherAdminService = voucherAdminService;
    }

    @Operation(operationId = "listVouchers", summary = "List every voucher",
            description = "Newest first, including expired and revoked ones — a list that hides "
                    + "what was withdrawn gives no way to see what happened.")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Vouchers with redemption counts"),
            @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content())
    })
    public Mono<ServerResponse> list(ServerRequest request) {
        return voucherAdminService.list().collectList()
                .flatMap(vouchers -> ServerResponse.ok().bodyValue(vouchers));
    }

    @Operation(operationId = "getVoucher", summary = "Get one voucher")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Voucher"),
            @ApiResponse(responseCode = "404", description = "No such voucher", content = @Content())
    })
    public Mono<ServerResponse> get(ServerRequest request) {
        return voucherAdminService.get(Long.valueOf(request.pathVariable("id")))
                .flatMap(voucher -> ServerResponse.ok().bodyValue(voucher));
    }

    @Operation(operationId = "createVoucher", summary = "Create a voucher",
            description = "ADMIN or MANAGER. An expiry date is required.")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Created"),
            @ApiResponse(responseCode = "400", description = "Invalid percentage or window", content = @Content()),
            @ApiResponse(responseCode = "409", description = "Code already exists", content = @Content()),
            @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content())
    })
    public Mono<ServerResponse> create(ServerRequest request) {
        return request.bodyToMono(CreateVoucherRequest.class)
                .zipWith(getUsername(request))
                .flatMap(tuple -> voucherAdminService.create(tuple.getT1(), tuple.getT2()))
                .flatMap(voucher -> ServerResponse.ok().bodyValue(voucher));
    }

    @Operation(operationId = "revokeVoucher", summary = "Withdraw a voucher",
            description = "ADMIN or MANAGER. Marks it revoked rather than deleting it: orders reference "
                    + "it, and placed orders keep the discount they were already charged.")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Revoked"),
            @ApiResponse(responseCode = "404", description = "No such voucher", content = @Content()),
            @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content())
    })
    public Mono<ServerResponse> revoke(ServerRequest request) {
        return voucherAdminService.revoke(Long.valueOf(request.pathVariable("id")))
                .flatMap(voucher -> ServerResponse.ok().bodyValue(voucher));
    }

    private Mono<String> getUsername(ServerRequest request) {
        return request.principal()
                .cast(Authentication.class)
                .map(auth -> ((Jwt) auth.getCredentials()).getSubject());
    }
}
