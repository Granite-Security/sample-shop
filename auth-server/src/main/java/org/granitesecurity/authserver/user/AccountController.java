package org.granitesecurity.authserver.user;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController {

    private final PasswordChangeService passwordChangeService;

    public AccountController(PasswordChangeService passwordChangeService) {
        this.passwordChangeService = passwordChangeService;
    }

    @PutMapping("/api/me/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody ChangePasswordRequest request) {
        passwordChangeService.changePassword(jwt.getSubject(), request);
        return ResponseEntity.noContent().build();
    }
}
