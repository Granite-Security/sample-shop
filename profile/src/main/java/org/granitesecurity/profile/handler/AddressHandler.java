package org.granitesecurity.profile.handler;

import org.granitesecurity.profile.dto.AddressRequest;
import org.granitesecurity.profile.service.AddressService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
public class AddressHandler {

    private static final Logger log = LoggerFactory.getLogger(AddressHandler.class);

    private final AddressService addressService;

    public AddressHandler(AddressService addressService) {
        this.addressService = addressService;
    }

    public Mono<ServerResponse> listAddresses(ServerRequest request) {
        return getUsername(request)
                .flatMapMany(addressService::getAddresses)
                .collectList()
                .flatMap(addresses -> ServerResponse.ok().bodyValue(addresses));
    }

    public Mono<ServerResponse> createAddress(ServerRequest request) {
        var bodyMono = request.bodyToMono(AddressRequest.class);
        var usernameMono = getUsername(request);
        return bodyMono.zipWith(usernameMono)
                .flatMap(tuple -> addressService.createAddress(tuple.getT2(), tuple.getT1()))
                .flatMap(addr -> ServerResponse.ok().bodyValue(addr));
    }

    public Mono<ServerResponse> updateAddress(ServerRequest request) {
        Long id = Long.valueOf(request.pathVariable("id"));
        var bodyMono = request.bodyToMono(AddressRequest.class);
        var usernameMono = getUsername(request);
        return bodyMono.zipWith(usernameMono)
                .flatMap(tuple -> addressService.updateAddress(id, tuple.getT2(), tuple.getT1()))
                .flatMap(addr -> ServerResponse.ok().bodyValue(addr))
                .onErrorResume(e -> {
                    log.error("Failed to update address {}: {}", id, e.getMessage());
                    return ServerResponse.badRequest().bodyValue(Map.of("error", e.getMessage()));
                });
    }

    public Mono<ServerResponse> deleteAddress(ServerRequest request) {
        Long id = Long.valueOf(request.pathVariable("id"));
        return getUsername(request)
                .flatMap(username -> addressService.deleteAddress(id, username))
                .then(ServerResponse.noContent().build());
    }

    public Mono<ServerResponse> getAddressById(ServerRequest request) {
        Long id = Long.valueOf(request.pathVariable("id"));
        String username = request.pathVariable("username");
        return addressService.getAddressByIdAndUsername(id, username)
                .flatMap(addr -> ServerResponse.ok().bodyValue(addr))
                .switchIfEmpty(ServerResponse.notFound().build());
    }

    private Mono<String> getUsername(ServerRequest request) {
        return request.principal()
                .cast(Authentication.class)
                .map(auth -> ((Jwt) auth.getCredentials()).getSubject());
    }
}
