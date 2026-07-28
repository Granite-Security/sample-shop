package org.granitesecurity.authserver.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The acting admin's username, recorded in `blocked_by`. auth-server does not
 * authenticate the admin — profile does, then tells us who it was.
 */
public record BlockUserRequest(@NotBlank @Size(max = 64) String actor) {
}
