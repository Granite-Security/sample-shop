package org.granitesecurity.profile.handler;

import org.granitesecurity.profile.dto.AdminUserView;
import org.granitesecurity.profile.service.AdminUserService;
import org.granitesecurity.profile.service.OrphanSweepService;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Service
public class AdminUserHandler {

    private final AdminUserService adminUserService;
    private final OrphanSweepService orphanSweepService;

    public AdminUserHandler(AdminUserService adminUserService,
                            OrphanSweepService orphanSweepService) {
        this.adminUserService = adminUserService;
        this.orphanSweepService = orphanSweepService;
    }

    public Mono<ServerResponse> listUsers(ServerRequest request) {
        return ServerResponse.ok().body(adminUserService.listUsers(), AdminUserView.class);
    }

    public Mono<ServerResponse> blockUser(ServerRequest request) {
        String username = request.pathVariable("username");
        return actor(request)
                .flatMap(actor -> adminUserService.block(username, actor))
                .flatMap(user -> ServerResponse.ok().bodyValue(user));
    }

    public Mono<ServerResponse> unblockUser(ServerRequest request) {
        String username = request.pathVariable("username");
        return actor(request)
                .flatMap(actor -> adminUserService.unblock(username, actor))
                .flatMap(user -> ServerResponse.ok().bodyValue(user));
    }

    public Mono<ServerResponse> unpublishUser(ServerRequest request) {
        String username = request.pathVariable("username");
        return actor(request)
                .flatMap(a -> adminUserService.unpublish(username, a))
                .then(ServerResponse.noContent().build());
    }

    public Mono<ServerResponse> deleteUser(ServerRequest request) {
        String username = request.pathVariable("username");
        return actor(request)
                .flatMap(actor -> adminUserService.delete(username, actor))
                // 200, not 204: BLOCKED_INSTEAD is a real outcome the UI has to
                // explain, so the body always matters.
                .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    /** Read-only cross-service reconciliation (§8 Phase 6). Deletes nothing. */
    public Mono<ServerResponse> orphanReport(ServerRequest request) {
        return orphanSweepService.sweep()
                .flatMap(report -> ServerResponse.ok().bodyValue(report));
    }

    /** The acting admin, taken from the JWT — never from the request body. */
    private Mono<String> actor(ServerRequest request) {
        return request.principal()
                .cast(Authentication.class)
                .map(auth -> ((Jwt) auth.getCredentials()).getSubject());
    }
}
