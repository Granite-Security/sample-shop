package org.granitesecurity.payment.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which storefront a shopper came from (docs/bugs/redirects.md §4.3).
 *
 * <p>One {@code payment} serves several domains, so "where do I send this shopper back to"
 * cannot be a config value — that is the whole bug this exists to fix. It is a property of
 * the request that opened the payment.
 *
 * <p>The design is copied from the one path that never had the bug: Stripe's return URL is
 * built in the browser as {@code window.location.origin}, and a browser cannot get its own
 * origin wrong. This reads the same value one layer down, off the request.
 *
 * <p><strong>Everything here is allow-listed.</strong> The resolved origin ends up in a
 * {@code 302 Location}, so an unchecked header would make this service an open redirect:
 * forge {@code Origin}, place an order, and our payment return bounces the shopper
 * anywhere. Untrusted input is not an error — it falls back to the configured origin,
 * which is exactly the behaviour that existed before this class.
 */
@Component
public class StorefrontOrigins {

    private static final Logger log = LoggerFactory.getLogger(StorefrontOrigins.class);

    private static final String FORWARDED_HOST = "X-Forwarded-Host";
    private static final String FORWARDED_PROTO = "X-Forwarded-Proto";

    private final Set<String> allowed;
    private final String fallback;

    public StorefrontOrigins(
            @Value("${app.storefront-origins:}") String allowedOrigins,
            @Value("${app.frontend-origin:http://localhost:5173}") String frontendOrigin) {
        this.fallback = normalise(frontendOrigin);
        Set<String> configured = Arrays.stream(allowedOrigins.split(","))
                .map(StorefrontOrigins::normalise)
                .filter(o -> !o.isEmpty())
                .collect(Collectors.toSet());
        // The configured fallback is always trusted: it is where every redirect went
        // before this class existed, and leaving it out would make a misconfigured
        // allow-list reject the very value it falls back to.
        configured.add(this.fallback);
        this.allowed = Set.copyOf(configured);
        log.info("Storefront origins: {} (fallback {})", this.allowed, this.fallback);
    }

    /**
     * The origin of the browser that made this request, or the configured fallback.
     *
     * <p>{@code Origin} first — it is literally {@code window.location.origin} and browsers
     * send it on the cross-origin and POST requests that matter here. Then the forwarded
     * headers the gateway sets, the same pair auth-server derives its per-request issuer
     * from. A caller that is not a browser at all (service-to-service) has neither, and
     * gets the fallback.
     */
    public String resolve(ServerRequest request) {
        HttpHeaders headers = request.headers().asHttpHeaders();

        String origin = headers.getFirst(HttpHeaders.ORIGIN);
        if (origin != null && !origin.isBlank()) {
            return sanitise(origin);
        }

        String host = headers.getFirst(FORWARDED_HOST);
        if (host != null && !host.isBlank()) {
            String proto = headers.getFirst(FORWARDED_PROTO);
            // A forwarded host can carry a comma-separated chain; the first entry is the
            // client-facing one.
            String firstHost = host.split(",")[0].trim();
            return sanitise((proto == null || proto.isBlank() ? "https" : proto.split(",")[0].trim())
                    + "://" + firstHost);
        }

        return fallback;
    }

    /**
     * Checks an origin that arrived from somewhere other than the current request — a
     * Kafka event, or a column written weeks ago. Null, blank and unrecognised all mean
     * "use the configured origin".
     *
     * <p>A stored origin is re-checked rather than trusted because it was validated against
     * the allow-list as it stood when it was written, and that list changes.
     */
    public String sanitise(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return fallback;
        }
        String normalised = normalise(candidate);
        if (allowed.contains(normalised)) {
            return normalised;
        }
        // Not an error: the shopper still gets a working page, just the default one.
        log.warn("Ignoring untrusted storefront origin '{}', falling back to {}", normalised, fallback);
        return fallback;
    }

    /** The configured origin, for callers with no request and nothing stored. */
    public String fallback() {
        return fallback;
    }

    /** Lower-cased and without a trailing slash, so the allow-list compares like for like. */
    private static String normalise(String origin) {
        if (origin == null) {
            return "";
        }
        String trimmed = origin.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.toLowerCase();
    }
}
