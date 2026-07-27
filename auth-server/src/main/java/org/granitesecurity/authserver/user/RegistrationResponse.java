package org.granitesecurity.authserver.user;

public record RegistrationResponse(
        String username,
        String email
) {
}
