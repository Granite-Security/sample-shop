package org.granitesecurity.shop.handler;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.granitesecurity.shop.dto.PackagingCapacityRequest;
import org.granitesecurity.shop.dto.PackagingGroupRequest;
import org.granitesecurity.shop.dto.PackagingOptionRequest;
import org.granitesecurity.shop.service.PackagingAdminService;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Admin CRUD for packaging. Guarded by ShopSec, not by the gateway — the gateway is
 * a pass-through proxy and adding a route there protects nothing.
 */
@Service
public class PackagingAdminHandler {

    private final PackagingAdminService packagingAdminService;

    public PackagingAdminHandler(PackagingAdminService packagingAdminService) {
        this.packagingAdminService = packagingAdminService;
    }

    @Operation(operationId = "listPackagingGroups", summary = "List packaging groups",
            description = "Admin only. Includes retired options, which is the only way to find one and reinstate it.")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Groups with their allowed options"),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN role", content = @Content())
    })
    public Mono<ServerResponse> listGroups(ServerRequest request) {
        return packagingAdminService.listGroups()
                .flatMap(groups -> ServerResponse.ok().bodyValue(groups));
    }

    @Operation(operationId = "createPackagingGroup", summary = "Create a packaging group", description = "Admin only")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Group created"),
            @ApiResponse(responseCode = "409", description = "Code already in use", content = @Content()),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN role", content = @Content())
    })
    public Mono<ServerResponse> createGroup(ServerRequest request) {
        return request.bodyToMono(PackagingGroupRequest.class)
                .flatMap(packagingAdminService::createGroup)
                .flatMap(group -> ServerResponse.ok().bodyValue(group));
    }

    @Operation(operationId = "updatePackagingGroup", summary = "Rename a packaging group",
            description = "Admin only. The code is immutable — events and fulfilment refer to it.")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Group updated"),
            @ApiResponse(responseCode = "404", description = "Group not found", content = @Content()),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN role", content = @Content())
    })
    public Mono<ServerResponse> updateGroup(ServerRequest request) {
        Long id = Long.valueOf(request.pathVariable("id"));
        return request.bodyToMono(PackagingGroupRequest.class)
                .flatMap(body -> packagingAdminService.updateGroup(id, body))
                .flatMap(group -> ServerResponse.ok().bodyValue(group));
    }

    @Operation(operationId = "listPackagingOptions", summary = "List packaging options", description = "Admin only")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Options, retired ones included"),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN role", content = @Content())
    })
    public Mono<ServerResponse> listOptions(ServerRequest request) {
        return packagingAdminService.listOptions().collectList()
                .flatMap(options -> ServerResponse.ok().bodyValue(options));
    }

    @Operation(operationId = "createPackagingOption", summary = "Create a packaging option",
            description = "Admin only. A unit cost is required — an unstated box cost goes unrecorded, not defaulted.")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Option created"),
            @ApiResponse(responseCode = "409", description = "Code already in use", content = @Content()),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN role", content = @Content())
    })
    public Mono<ServerResponse> createOption(ServerRequest request) {
        return request.bodyToMono(PackagingOptionRequest.class)
                .flatMap(packagingAdminService::createOption)
                .flatMap(option -> ServerResponse.ok().bodyValue(option));
    }

    @Operation(operationId = "updatePackagingOption", summary = "Reprice or retire a packaging option",
            description = "Admin only. Refused when retiring would leave a group with no box at all. "
                    + "Repricing never touches orders already placed.")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Option updated"),
            @ApiResponse(responseCode = "404", description = "Option not found", content = @Content()),
            @ApiResponse(responseCode = "409", description = "Would leave a group unpackageable", content = @Content()),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN role", content = @Content())
    })
    public Mono<ServerResponse> updateOption(ServerRequest request) {
        Long id = Long.valueOf(request.pathVariable("id"));
        return request.bodyToMono(PackagingOptionRequest.class)
                .flatMap(body -> packagingAdminService.updateOption(id, body))
                .flatMap(option -> ServerResponse.ok().bodyValue(option));
    }

    @Operation(operationId = "setPackagingCapacity", summary = "Allow an option for a group, or set how many fit",
            description = "Admin only. Upsert: creates the pairing if it does not exist.")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Group with its updated options"),
            @ApiResponse(responseCode = "404", description = "Group or option not found", content = @Content()),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN role", content = @Content())
    })
    public Mono<ServerResponse> setCapacity(ServerRequest request) {
        Long groupId = Long.valueOf(request.pathVariable("id"));
        return request.bodyToMono(PackagingCapacityRequest.class)
                .flatMap(body -> packagingAdminService.setCapacity(groupId, body))
                .flatMap(group -> ServerResponse.ok().bodyValue(group));
    }

    @Operation(operationId = "removePackagingCapacity", summary = "Stop offering an option for a group",
            description = "Admin only. Refused when it is the group's last available box.")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Group with its remaining options"),
            @ApiResponse(responseCode = "404", description = "Pairing not found", content = @Content()),
            @ApiResponse(responseCode = "409", description = "Would leave the group unpackageable", content = @Content()),
            @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN role", content = @Content())
    })
    public Mono<ServerResponse> removeCapacity(ServerRequest request) {
        Long groupId = Long.valueOf(request.pathVariable("id"));
        Long optionId = Long.valueOf(request.pathVariable("optionId"));
        return packagingAdminService.removeCapacity(groupId, optionId)
                .flatMap(group -> ServerResponse.ok().bodyValue(group));
    }
}
