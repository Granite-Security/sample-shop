package org.granitesecurity.authserver.user;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal, machine-to-machine only. The filter chain in SecurityConfig gates
 * this on SCOPE_identity.admin, which only the identity-admin client can
 * obtain — a SCOPE_internal token gets 403 (docs/users/blocking-users.md §3.1).
 */
@RestController
@RequestMapping("/api/internal/users")
public class InternalUserController {

    private final UserAdminService userAdminService;

    public InternalUserController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @GetMapping
    public List<AdminUserResponse> list() {
        return userAdminService.listUsers();
    }

    @PostMapping("/{username}/block")
    public AdminUserResponse block(@PathVariable String username,
                                   @Valid @RequestBody BlockUserRequest request) {
        return userAdminService.block(username, request.actor());
    }

    @PostMapping("/{username}/unblock")
    public AdminUserResponse unblock(@PathVariable String username) {
        return userAdminService.unblock(username);
    }

    @DeleteMapping("/{username}")
    public ResponseEntity<Void> delete(@PathVariable String username) {
        userAdminService.delete(username);
        return ResponseEntity.noContent().build();
    }
}
