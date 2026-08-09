package org.granitesecurity.accounting.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI for reading the books by hand.
 *
 * <pre>kubectl -n granite port-forward deploy/accounting 8068:8068</pre>
 *
 * <p>Reached by port-forwarding to the pod, not through the gateway: there is no
 * HTTPRoute for accounting and there should not be one. Every operation needs a
 * ROLE_ADMIN token pasted into <b>Authorize</b>.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Accounting API",
                version = "1.0.0",
                description = """
                        The books: journal entries derived from domain events, and the
                        accrual reports served from them. Read-only and admin-only — this
                        service projects, it never moves money (balance is the only writer).
                        All amounts are rappen. See docs/finance/accounting.md."""
        ),
        servers = @Server(url = "/", description = "Port-forward to the accounting pod")
)
@SecurityScheme(
        name = "bearer-jwt",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "OAuth2 JWT issued by the auth-server. Everything here needs ROLE_ADMIN."
)
public class OpenApiConfig {
}
