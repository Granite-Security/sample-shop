package org.granitesecurity.balance.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI for poking the ledger by hand.
 *
 * <p>Reached by port-forwarding straight to the pod, not through the gateway —
 * there is no HTTPRoute for it and there should not be, since it would put a
 * money-moving API behind a public URL with a try-it-out button:
 *
 * <pre>kubectl -n granite port-forward deploy/balance 8067:8067</pre>
 *
 * <p>The endpoints still need a real JWT. Paste one into <b>Authorize</b>; a
 * ROLE_ADMIN token is required for anything under /admin.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Balance API",
                version = "1.0.0",
                description = """
                        The platform's central bank. Double-entry CHF ledger: user balances,
                        house accounts, admin gifting and reconciliation.
                        All amounts in responses are rappen (balanceMinor) alongside CHF.
                        See docs/finance/finance.md."""
        ),
        servers = @Server(url = "/", description = "Port-forward to the balance pod")
)
@SecurityScheme(
        name = "bearer-jwt",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "OAuth2 JWT issued by the auth-server. Admin routes need ROLE_ADMIN."
)
public class OpenApiConfig {
}
