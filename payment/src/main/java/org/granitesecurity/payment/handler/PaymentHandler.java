package org.granitesecurity.payment.handler;

import org.granitesecurity.payment.dto.CreatePaymentIntentRequest;
import org.granitesecurity.payment.provider.PaymentProviderRegistry;
import org.granitesecurity.payment.web.StorefrontOrigins;
import org.granitesecurity.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Service
public class PaymentHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentHandler.class);

    private final PaymentService paymentService;
    private final PaymentProviderRegistry providers;
    private final StorefrontOrigins storefrontOrigins;

    public PaymentHandler(PaymentService paymentService, PaymentProviderRegistry providers,
                          StorefrontOrigins storefrontOrigins) {
        this.paymentService = paymentService;
        this.providers = providers;
        this.storefrontOrigins = storefrontOrigins;
    }

    /**
     * Public: the checkout page needs this before the shopper is authenticated, and it
     * leaks nothing — names, labels and how each provider is confirmed. The frontend
     * renders a selector only when this returns more than one.
     */
    public Mono<ServerResponse> listProviders(ServerRequest request) {
        var body = providers.enabled().stream()
                .map(p -> Map.of(
                        "id", p.name(),
                        "displayName", p.displayName(),
                        "confirmationMode", p.confirmationMode().name(),
                        "webhookEnabled", p.webhookEnabled()))
                .toList();
        return ServerResponse.ok().bodyValue(body);
    }

    /**
     * Where a redirect provider sends the shopper back to. Captures the payment, then
     * bounces the browser to the order page.
     *
     * <p><b>303, not 302.</b> The shopper arrives here by GET and must continue by GET;
     * the same reasoning as auth-server's post-login redirect.
     *
     * <p>Everything on the query string is attacker-controllable — this URL is handed to
     * a third party and the shopper's browser follows it. Only {@code orderId} is read,
     * and it is used solely to look up a row whose provider is then checked against the
     * path. The capture call is what decides whether money moved; nothing the caller
     * sends can assert a payment succeeded.
     *
     * <p>Always redirects, even on failure. A shopper who sees a stack trace instead of
     * their order has lost the thread entirely — the order page shows the payment as
     * unpaid and offers a retry, which is the honest outcome.
     */
    public Mono<ServerResponse> handleReturn(ServerRequest request) {
        String provider = request.pathVariable("provider");

        // A top-up has no order, so it comes back keyed on the payment itself
        // (docs/finance/finance.md §6.1).
        var topupId = request.queryParam("paymentId");
        if (topupId.isPresent()) {
            java.util.UUID paymentId;
            try {
                paymentId = java.util.UUID.fromString(topupId.get());
            } catch (IllegalArgumentException e) {
                log.warn("Return from '{}' with an unusable paymentId", provider);
                // Nothing to look the origin up by, so the configured one it is.
                return redirectTo(paymentService.balancePageUrl(null));
            }
            // The shopper is arriving from the provider, so this request's Origin is
            // PayPal's, not the storefront's. The payment row is the only thing that
            // remembers where they started (docs/bugs/redirects.md §4.1).
            return paymentService.finalizeRedirectTopup(provider, paymentId)
                    .flatMap(payment -> redirectTo(paymentService.balancePageUrl(payment.getStorefrontOrigin())))
                    .onErrorResume(e -> {
                        log.error("Return from '{}' for top-up {} failed: {}",
                                provider, paymentId, e.getMessage(), e);
                        // The finalize failed, but the shopper must still land somewhere
                        // sensible — read the origin back on its own.
                        return paymentService.topupOriginFor(paymentId)
                                .flatMap(origin -> redirectTo(paymentService.balancePageUrl(origin)));
                    });
        }

        Long orderId;
        try {
            orderId = Long.parseLong(request.queryParam("orderId").orElseThrow());
        } catch (RuntimeException e) {
            log.warn("Return from '{}' with no usable orderId", provider);
            return redirectTo(paymentService.ordersPageUrl(null));
        }

        // Same as the top-up above: the origin comes from the stored payment, never from
        // the provider's redirect.
        return paymentService.finalizeRedirectPayment(provider, orderId)
                .then(paymentService.orderPageUrlFor(orderId))
                .flatMap(this::redirectTo)
                .onErrorResume(e -> {
                    log.error("Return from '{}' for order {} failed: {}", provider, orderId, e.getMessage(), e);
                    return paymentService.orderPageUrlFor(orderId).flatMap(this::redirectTo);
                });
    }

    private Mono<ServerResponse> redirectTo(String url) {
        return ServerResponse.status(303).location(java.net.URI.create(url)).build();
    }

    /** Opens a top-up. The owner is the JWT subject, never a body field. */
    public Mono<ServerResponse> createTopupIntent(ServerRequest request) {
        return request.principal()
                .cast(org.springframework.security.core.Authentication.class)
                .map(auth -> ((org.springframework.security.oauth2.jwt.Jwt) auth.getCredentials()).getSubject())
                .flatMap(username -> request.bodyToMono(TopupRequest.class)
                        .switchIfEmpty(Mono.error(new IllegalArgumentException("An amount is required")))
                        .flatMap(body -> paymentService.createTopupIntent(
                                username, body.amount(), body.currency(), body.provider(),
                                // The browser's own origin, the same value the Stripe path
                                // has always used (docs/bugs/redirects.md §4.3).
                                storefrontOrigins.resolve(request))))
                .flatMap(payment -> ServerResponse.status(201).bodyValue(
                        PaymentService.toResponse(payment, null)))
                .onErrorResume(PaymentProviderRegistry.UnknownProviderException.class,
                        e -> ServerResponse.badRequest().bodyValue(Map.of("error", e.getMessage())))
                .onErrorResume(IllegalArgumentException.class,
                        e -> ServerResponse.badRequest().bodyValue(Map.of("error", e.getMessage())));
    }

    /**
     * Confirms a top-up. Unlike an order, this is the <b>only</b> reliable path: the
     * provider webhooks resolve payments through an order id, and a top-up has none.
     */
    public Mono<ServerResponse> syncTopup(ServerRequest request) {
        java.util.UUID paymentId;
        try {
            paymentId = java.util.UUID.fromString(request.pathVariable("paymentId"));
        } catch (IllegalArgumentException e) {
            return ServerResponse.badRequest().bodyValue(Map.of("error", "Not a payment id"));
        }
        return paymentService.syncTopup(paymentId)
                .flatMap(payment -> ServerResponse.ok().bodyValue(
                        PaymentService.toResponse(payment, null)));
    }

    /** What the frontend needs to complete a top-up (client secret or redirect URL). */
    public record TopupRequest(java.math.BigDecimal amount, String currency, String provider) {}

    public Mono<ServerResponse> createPaymentIntent(ServerRequest request) {
        return request.bodyToMono(CreatePaymentIntentRequest.class)
                .flatMap(req -> paymentService.createPaymentIntent(
                        req.orderId(), req.total(), req.currency(), req.username(), req.provider(),
                        storefrontOrigins.resolve(request)))
                .flatMap(payment -> ServerResponse.ok().bodyValue(
                        PaymentService.toResponse(payment, null)))
                // A provider the shopper named that we do not have, or none named while
                // several are enabled, is a bad request — not a 500, and not a silent
                // choice made on their behalf.
                .onErrorResume(PaymentProviderRegistry.UnknownProviderException.class,
                        e -> ServerResponse.badRequest().bodyValue(Map.of("error", e.getMessage())))
                .onErrorResume(PaymentProviderRegistry.AmbiguousProviderException.class,
                        e -> ServerResponse.badRequest().bodyValue(Map.of("error", e.getMessage())));
    }

    public Mono<ServerResponse> getPaymentByOrderId(ServerRequest request) {
        Long orderId = Long.valueOf(request.pathVariable("orderId"));
        return paymentService.getPaymentByOrderId(orderId)
                .flatMap(response -> ServerResponse.ok().bodyValue(response))
                .switchIfEmpty(ServerResponse.notFound().build());
    }

    // Internal, SCOPE_internal only. shop asks this before purging a user's
    // orders, because order status cannot tell you whether money moved
    // (docs/users/blocking-users.md §2.3).
    public Mono<ServerResponse> getStatusesByOrderIds(ServerRequest request) {
        return request.bodyToMono(OrderIdsRequest.class)
                .map(req -> req.orderIds() == null ? List.<Long>of() : req.orderIds())
                .flatMap(paymentService::statusesByOrderIds)
                .flatMap(statuses -> ServerResponse.ok().bodyValue(statuses));
    }

    public record OrderIdsRequest(List<Long> orderIds) {}

    // Orphan sweep (docs/users/blocking-users.md §8 Phase 6), read-only.
    public Mono<ServerResponse> getOrderIds(ServerRequest request) {
        return paymentService.distinctOrderIds()
                .flatMap(orderIds -> ServerResponse.ok().bodyValue(orderIds));
    }

    public Mono<ServerResponse> retryPaymentIntent(ServerRequest request) {
        Long orderId = Long.valueOf(request.pathVariable("orderId"));
        return paymentService.retryPaymentIntent(orderId)
                .flatMap(payment -> ServerResponse.ok().bodyValue(
                        PaymentService.toResponse(payment, null)))
                .onErrorResume(e -> {
                    log.error("Failed to retry payment for order {}: {}", orderId, e.getMessage());
                    return ServerResponse.badRequest().bodyValue(Map.of("error", e.getMessage()));
                });
    }

    public Mono<ServerResponse> syncPaymentStatus(ServerRequest request) {
        Long orderId = Long.valueOf(request.pathVariable("orderId"));
        return paymentService.syncPaymentStatus(orderId)
                .flatMap(response -> ServerResponse.ok().bodyValue(response))
                .onErrorResume(e -> {
                    log.error("Failed to sync payment status for order {}: {}", orderId, e.getMessage());
                    return ServerResponse.badRequest().bodyValue(Map.of("error", e.getMessage()));
                });
    }
}
