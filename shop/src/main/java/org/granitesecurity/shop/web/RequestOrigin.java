package org.granitesecurity.shop.web;

import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.server.ServerRequest;

/**
 * Which storefront a request came from (docs/bugs/redirects.md §4.1).
 *
 * <p>`shop` is the only service in the order→payment chain that runs inside the shopper's
 * HTTP request. `payment` opens the intent from a Kafka event with no request in flight,
 * so if this is not captured here it cannot be recovered later — that is the whole reason
 * redirect payments landed on the wrong domain.
 *
 * <p><strong>This does not validate anything, deliberately.</strong> The value is recorded
 * and republished, never acted on: shop does not redirect anybody. The allow-list lives in
 * `payment`'s {@code StorefrontOrigins}, at the point where the string would actually
 * become a {@code 302 Location}. Duplicating it here would mean two lists to keep in sync
 * and would still not be the one that matters. Treat anything this returns as untrusted.
 */
public final class RequestOrigin {

    private static final String FORWARDED_HOST = "X-Forwarded-Host";
    private static final String FORWARDED_PROTO = "X-Forwarded-Proto";

    private RequestOrigin() {}

    /**
     * The browser's own origin, or null when the caller is not a browser.
     *
     * <p>{@code Origin} first — it is exactly {@code window.location.origin}, the value the
     * Stripe path has always used correctly. Then the gateway's forwarded headers. Null
     * rather than a default: "we do not know" is information payment needs, and it has a
     * configured fallback of its own.
     */
    public static String from(ServerRequest request) {
        HttpHeaders headers = request.headers().asHttpHeaders();

        String origin = headers.getFirst(HttpHeaders.ORIGIN);
        if (origin != null && !origin.isBlank()) {
            return trimSlash(origin.trim());
        }

        String host = headers.getFirst(FORWARDED_HOST);
        if (host == null || host.isBlank()) {
            return null;
        }
        // Either header can carry a comma-separated proxy chain; the first hop is the
        // client-facing one.
        String proto = headers.getFirst(FORWARDED_PROTO);
        String scheme = (proto == null || proto.isBlank()) ? "https" : proto.split(",")[0].trim();
        return trimSlash(scheme + "://" + host.split(",")[0].trim());
    }

    private static String trimSlash(String url) {
        String trimmed = url;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
