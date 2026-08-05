package org.granitesecurity.payment.provider.paypal;

import org.granitesecurity.payment.domain.PaymentStatus;
import org.granitesecurity.payment.domain.RefundStatus;
import org.granitesecurity.payment.provider.ConfirmationMode;
import org.granitesecurity.payment.provider.CreateIntentRequest;
import org.granitesecurity.payment.provider.Money;
import org.granitesecurity.payment.provider.PaymentProviderException;
import org.granitesecurity.payment.provider.ProviderHealth;
import org.granitesecurity.payment.provider.ProviderIntent;
import org.granitesecurity.payment.provider.ProviderRefund;
import org.granitesecurity.payment.provider.ProviderWebhookEvent;
import org.granitesecurity.payment.provider.RedirectPaymentProvider;
import org.granitesecurity.payment.provider.WebhookVerificationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PayPal Orders v2 behind the {@link RedirectPaymentProvider} port.
 *
 * <p><b>The thing to understand before changing anything here:</b> PayPal is not
 * intent-shaped like Stripe. An order reaches {@code APPROVED} when the shopper says
 * yes, and <b>no money has moved at that point</b>. Only {@code POST /capture} charges
 * it. Every status mapping below turns on that distinction — mapping {@code APPROVED}
 * to SUCCEEDED would publish {@code PaymentReceived} and ship goods for free.
 *
 * <p>Raw {@code WebClient} rather than PayPal's SDK: the official
 * {@code paypal-server-sdk} is blocking, and this service is WebFlux + R2DBC end to
 * end. Orders v2 is a handful of endpoints — the SDK buys little and costs the
 * non-blocking property.
 */
@Component
@ConditionalOnProperty(name = "payment.providers.paypal.enabled", havingValue = "true")
public class PayPalPaymentProvider implements RedirectPaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(PayPalPaymentProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The two-decimal subset of PayPal's settlement currencies that this shop allows.
     * RON is absent because PayPal does not settle it — that is the whole reason RON
     * was dropped from the shop's closed set (docs/payment/paypal.md §6).
     */
    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("USD", "EUR", "CHF");

    /** Refreshed this long before PayPal's stated expiry, so a call never races it. */
    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofMinutes(5);

    private final WebClient http;

    @Value("${paypal.client-id:}")
    private String clientId;

    @Value("${paypal.client-secret:}")
    private String clientSecret;

    @Value("${paypal.webhook-id:}")
    private String webhookId;

    @Value("${paypal.env:sandbox}")
    private String env;

    @Value("${payment.providers.paypal.webhook.enabled:false}")
    private boolean webhookEnabled;

    /**
     * Cached bearer token. Two threads racing here both fetch and one wins — harmless,
     * PayPal issues concurrent tokens happily, and the alternative is a lock on the
     * request path to save an occasional duplicate HTTP call.
     */
    private volatile CachedToken cachedToken;

    public PayPalPaymentProvider(WebClient payPalWebClient) {
        this.http = payPalWebClient;
    }

    private record CachedToken(String value, Instant expiresAt) {
        boolean isFresh() {
            return Instant.now().isBefore(expiresAt.minus(TOKEN_REFRESH_MARGIN));
        }
    }

    @Override
    public String name() {
        return "paypal";
    }

    @Override
    public String displayName() {
        return "PayPal";
    }

    /**
     * Unlike Stripe, where the webhook is redundant with {@code /sync}, PayPal needs it:
     * a shopper who approves and closes the tab never reaches the return endpoint, and
     * {@code CHECKOUT.ORDER.APPROVED} is the only other thing that will capture.
     */
    @Override
    public boolean webhookEnabled() {
        return webhookEnabled;
    }

    @Override
    public ConfirmationMode confirmationMode() {
        return ConfirmationMode.REDIRECT;
    }

    @Override
    public Set<String> supportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @Override
    public Mono<ProviderHealth> health() {
        // Fetching a token is the cheapest call that proves both reachability and that
        // the credentials are the ones PayPal expects.
        return accessToken()
                .map(t -> ProviderHealth.up(Map.of(
                        "paypal", "connected",
                        "mode", env == null ? "unknown" : env)))
                .onErrorResume(e -> {
                    log.warn("PayPal health check failed: {}", e.getMessage());
                    return Mono.just(ProviderHealth.down(e.getMessage()));
                });
    }

    // ── Creating an order ────────────────────────────────────────────────

    @Override
    public Mono<ProviderIntent> createIntent(CreateIntentRequest request) {
        return createOrder(request)
                .onErrorMap(notAlreadyWrapped(), e ->
                        wrap("create order for order " + request.orderId(), e));
    }

    /**
     * An abandoned PayPal order needs no explicit void — it expires on its own — so a
     * retry is simply a fresh order under a new idempotency key.
     */
    @Override
    public Mono<ProviderIntent> recreateIntent(CreateIntentRequest request, String previousProviderPaymentId) {
        return createOrder(request)
                .doOnSuccess(intent -> log.info("Recreated PayPal order {} for order {} (previous {})",
                        intent.providerPaymentId(), request.orderId(), previousProviderPaymentId))
                .onErrorMap(notAlreadyWrapped(), e ->
                        wrap("recreate order for order " + request.orderId(), e));
    }

    private Mono<ProviderIntent> createOrder(CreateIntentRequest request) {
        Map<String, Object> body = Map.of(
                "intent", "CAPTURE",
                "purchase_units", List.of(purchaseUnit(request)),
                "payment_source", Map.of("paypal", Map.of(
                        "experience_context", experienceContext(request))));

        return accessToken().flatMap(token -> post("/v2/checkout/orders", token, body,
                        // PayPal's idempotency header. Same key replayed returns the
                        // original order rather than opening a second one — which is what
                        // makes an at-least-once OrderPlaced redelivery safe.
                        Map.of("PayPal-Request-Id", request.idempotencyKey())))
                .map(this::toProviderIntent);
    }

    private Map<String, Object> purchaseUnit(CreateIntentRequest request) {
        Map<String, Object> unit = new LinkedHashMap<>();
        unit.put("reference_id", String.valueOf(request.orderId()));
        // custom_id is what comes back on the capture webhook, and it is how an inbound
        // event is traced to a payment row — the equivalent of Stripe's metadata.order_id.
        // invoice_id is deliberately NOT set: PayPal enforces uniqueness on it, so a
        // retry of the same shop order would be rejected as a duplicate invoice.
        unit.put("custom_id", String.valueOf(request.orderId()));
        unit.put("amount", Map.of(
                "currency_code", request.amount().currency(),
                "value", decimalString(request.amount())));
        return unit;
    }

    private Map<String, Object> experienceContext(CreateIntentRequest request) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("return_url", request.returnUrl());
        ctx.put("cancel_url", request.cancelUrl());
        // PAY_NOW so the PayPal button reads as final rather than "Continue" — the
        // shopper has already reviewed the order on our side.
        ctx.put("user_action", "PAY_NOW");
        // The shop collects the delivery address itself and puts it on the order; asking
        // PayPal for one again would produce a second, divergent address.
        ctx.put("shipping_preference", "NO_SHIPPING");
        return ctx;
    }

    // ── Reading and finalizing ───────────────────────────────────────────

    @Override
    public Mono<ProviderIntent> retrieveIntent(String providerPaymentId) {
        return accessToken()
                .flatMap(token -> get("/v2/checkout/orders/" + providerPaymentId, token))
                .map(this::toProviderIntent)
                .onErrorMap(notAlreadyWrapped(), e -> wrap("retrieve order " + providerPaymentId, e));
    }

    /**
     * Takes the money for an approved order.
     *
     * <p>Idempotent by re-reading rather than by trusting a key: the return endpoint and
     * the {@code CHECKOUT.ORDER.APPROVED} webhook both land here, routinely at the same
     * time. An order that is already {@code COMPLETED} is reported from its current
     * state, and a capture that loses the race comes back as
     * {@code ORDER_ALREADY_CAPTURED}, which is success — the money is there.
     */
    @Override
    public Mono<ProviderIntent> finalizePayment(String providerPaymentId) {
        return accessToken().flatMap(token ->
                        get("/v2/checkout/orders/" + providerPaymentId, token)
                                .flatMap(order -> {
                                    String status = text(order, "status");
                                    if ("COMPLETED".equals(status)) {
                                        log.info("PayPal order {} already captured", providerPaymentId);
                                        return Mono.just(order);
                                    }
                                    if (!"APPROVED".equals(status)) {
                                        // Still waiting on the shopper. Not an error, and
                                        // emphatically not a transition — toProviderIntent
                                        // maps this to a null status.
                                        log.info("PayPal order {} is {} — nothing to capture yet",
                                                providerPaymentId, status);
                                        return Mono.just(order);
                                    }
                                    return capture(providerPaymentId, token);
                                }))
                .map(this::toProviderIntent)
                .onErrorMap(notAlreadyWrapped(), e -> wrap("finalize order " + providerPaymentId, e));
    }

    private Mono<JsonNode> capture(String orderId, String token) {
        return post("/v2/checkout/orders/" + orderId + "/capture", token, Map.of(),
                Map.of("PayPal-Request-Id", "capture-" + orderId))
                .doOnSuccess(o -> log.info("Captured PayPal order {}", orderId))
                .onErrorResume(PayPalApiException.class, e -> {
                    if (e.hasIssue("ORDER_ALREADY_CAPTURED")) {
                        // The other path won. The money is captured either way, so this
                        // is a success — re-read to report the real current state.
                        log.info("PayPal order {} was already captured by another path", orderId);
                        return get("/v2/checkout/orders/" + orderId, token);
                    }
                    return Mono.error(e);
                });
    }

    // ── Refunds ─────────────────────────────────────────────────────────

    /**
     * @param providerPaymentId the PayPal <b>order</b> id, which is what
     *                          {@code payment.provider_payment_id} holds. PayPal refunds
     *                          against a <b>capture</b> id, so it is resolved from the
     *                          order first. One extra call, and no schema change for a
     *                          field only refunds need.
     */
    @Override
    public Mono<ProviderRefund> createRefund(String providerPaymentId, Money amount, String idempotencyKey) {
        return accessToken().flatMap(token -> captureIdOf(providerPaymentId, token)
                        .flatMap(captureId -> post("/v2/payments/captures/" + captureId + "/refund", token,
                                Map.of("amount", Map.of(
                                        "currency_code", amount.currency(),
                                        "value", decimalString(amount))),
                                Map.of("PayPal-Request-Id", idempotencyKey))))
                .map(this::toProviderRefund)
                .onErrorMap(notAlreadyWrapped(), e -> wrap("create refund for " + providerPaymentId, e));
    }

    @Override
    public Mono<ProviderRefund> retrieveRefund(String providerRefundId) {
        return accessToken()
                .flatMap(token -> get("/v2/payments/refunds/" + providerRefundId, token))
                .map(this::toProviderRefund)
                .onErrorMap(notAlreadyWrapped(), e -> wrap("retrieve refund " + providerRefundId, e));
    }

    private Mono<String> captureIdOf(String orderId, String token) {
        return get("/v2/checkout/orders/" + orderId, token)
                .flatMap(order -> {
                    String captureId = firstCaptureId(order);
                    if (captureId == null) {
                        return Mono.error(new PaymentProviderException(name(),
                                "PayPal order " + orderId + " has no capture to refund", null));
                    }
                    return Mono.just(captureId);
                });
    }

    private static String firstCaptureId(JsonNode order) {
        JsonNode units = order.get("purchase_units");
        if (units == null || !units.isArray()) {
            return null;
        }
        for (JsonNode unit : units) {
            JsonNode captures = unit.path("payments").path("captures");
            if (captures.isArray()) {
                for (JsonNode capture : captures) {
                    String id = text(capture, "id");
                    if (id != null) {
                        return id;
                    }
                }
            }
        }
        return null;
    }

    // ── Webhooks ────────────────────────────────────────────────────────

    /**
     * PayPal's documented verification is a call to PayPal, not local crypto — which is
     * why {@code parseWebhook} returns a {@code Mono} on the port at all. Doing it
     * synchronously would put a network round trip on an event loop thread.
     */
    @Override
    public Mono<ProviderWebhookEvent> parseWebhook(String payload, Map<String, String> headers) {
        if (webhookId == null || webhookId.isBlank()) {
            // Without the id there is nothing to verify against, so every delivery would
            // be indistinguishable from a forged one.
            return Mono.error(new WebhookVerificationException(
                    "paypal.webhook-id is not set — cannot verify the delivery"));
        }

        JsonNode event;
        try {
            event = MAPPER.readTree(payload);
        } catch (Exception e) {
            return Mono.error(new WebhookVerificationException("Webhook body is not JSON", e));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("auth_algo", header(headers, "paypal-auth-algo"));
        body.put("cert_url", header(headers, "paypal-cert-url"));
        body.put("transmission_id", header(headers, "paypal-transmission-id"));
        body.put("transmission_sig", header(headers, "paypal-transmission-sig"));
        body.put("transmission_time", header(headers, "paypal-transmission-time"));
        body.put("webhook_id", webhookId);
        body.put("webhook_event", event);

        for (var entry : body.entrySet()) {
            if (entry.getValue() == null) {
                return Mono.error(new WebhookVerificationException(
                        "Missing PayPal signature header: " + entry.getKey()));
            }
        }

        return accessToken()
                .flatMap(token -> post("/v1/notifications/verify-webhook-signature", token, body, Map.of()))
                .flatMap(result -> {
                    String status = text(result, "verification_status");
                    if (!"SUCCESS".equals(status)) {
                        return Mono.error(new WebhookVerificationException(
                                "PayPal reported verification_status=" + status));
                    }
                    return Mono.just(translate(event));
                });
    }

    /**
     * Translates a verified event. The one that matters is
     * {@code CHECKOUT.ORDER.APPROVED}: it carries no payment status because approval is
     * not payment — it asks us to capture.
     */
    private ProviderWebhookEvent translate(JsonNode event) {
        String eventId = text(event, "id");
        String eventType = text(event, "event_type");
        JsonNode resource = event.path("resource");

        if ("CHECKOUT.ORDER.APPROVED".equals(eventType)) {
            return ProviderWebhookEvent.approval(eventId, eventType,
                    orderIdOf(resource, eventId), text(resource, "id"));
        }

        RefundStatus refundStatus = mapRefundEventType(eventType);
        if (refundStatus != null) {
            return ProviderWebhookEvent.refund(eventId, eventType, refundStatus,
                    text(resource, "id"), relatedOrderId(resource));
        }

        PaymentStatus status = mapCaptureEventType(eventType);
        // The capture resource's own order id lives under supplementary_data; its
        // custom_id is the shop order id we put on the purchase unit.
        return ProviderWebhookEvent.payment(eventId, eventType, orderIdOf(resource, eventId),
                status, relatedOrderId(resource));
    }

    /** Our shop order id, from the custom_id echoed back on the resource. */
    private Long orderIdOf(JsonNode resource, String eventId) {
        String raw = text(resource, "custom_id");
        if (raw == null) {
            // On CHECKOUT.ORDER.* the resource is the order itself, so custom_id sits on
            // the purchase unit rather than the top level.
            JsonNode units = resource.get("purchase_units");
            if (units != null && units.isArray() && !units.isEmpty()) {
                raw = text(units.get(0), "custom_id");
            }
        }
        if (raw == null || raw.isBlank()) {
            log.warn("PayPal event {} carries no custom_id — cannot resolve the order", eventId);
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("PayPal event {} has non-numeric custom_id: {}", eventId, raw);
            return null;
        }
    }

    /** The PayPal order id a capture or refund belongs to, when the event carries it. */
    private static String relatedOrderId(JsonNode resource) {
        String related = text(resource.path("supplementary_data").path("related_ids"), "order_id");
        return related != null ? related : text(resource, "id");
    }

    // ── Translation ─────────────────────────────────────────────────────

    private ProviderIntent toProviderIntent(JsonNode order) {
        String rawStatus = text(order, "status");
        Map<String, Object> payload = new LinkedHashMap<>();
        String redirectUrl = payerActionLink(order);
        if (redirectUrl != null) {
            payload.put("redirectUrl", redirectUrl);
        }
        return new ProviderIntent(
                text(order, "id"),
                mapOrderStatus(rawStatus, order),
                rawStatus,
                payload,
                redirectUrl,
                declineReasonOf(order));
    }

    /**
     * Where to send the shopper. {@code payer-action} is the rel PayPal returns when the
     * order specifies a {@code payment_source}; {@code approve} is the older name and is
     * still what some responses carry.
     */
    private static String payerActionLink(JsonNode order) {
        JsonNode links = order.get("links");
        if (links == null || !links.isArray()) {
            return null;
        }
        for (JsonNode link : links) {
            String rel = text(link, "rel");
            if ("payer-action".equals(rel) || "approve".equals(rel)) {
                return text(link, "href");
            }
        }
        return null;
    }

    private static String declineReasonOf(JsonNode order) {
        JsonNode units = order.get("purchase_units");
        if (units == null || !units.isArray()) {
            return null;
        }
        for (JsonNode unit : units) {
            JsonNode captures = unit.path("payments").path("captures");
            if (captures.isArray()) {
                for (JsonNode capture : captures) {
                    String reason = text(capture.path("status_details"), "reason");
                    if (reason != null) {
                        return reason;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Order status → ours. <b>Null means "no transition"</b>, and {@code APPROVED} maps
     * to null on purpose: the shopper has agreed but the money has not moved. Treating
     * it as SUCCEEDED is the single most expensive mistake available in this file.
     *
     * <p>A COMPLETED order defers to its capture's status, because an order can complete
     * with a capture that is DECLINED or still PENDING.
     */
    static PaymentStatus mapOrderStatus(String orderStatus, JsonNode order) {
        if (orderStatus == null) {
            return null;
        }
        return switch (orderStatus) {
            case "COMPLETED" -> captureStatus(order);
            case "VOIDED" -> PaymentStatus.CANCELED;
            // CREATED, SAVED, PAYER_ACTION_REQUIRED, APPROVED: no money has moved.
            default -> null;
        };
    }

    private static PaymentStatus captureStatus(JsonNode order) {
        JsonNode units = order.get("purchase_units");
        if (units == null || !units.isArray()) {
            return PaymentStatus.SUCCEEDED;
        }
        for (JsonNode unit : units) {
            JsonNode captures = unit.path("payments").path("captures");
            if (captures.isArray()) {
                for (JsonNode capture : captures) {
                    PaymentStatus mapped = mapCaptureStatus(text(capture, "status"));
                    if (mapped != null) {
                        return mapped;
                    }
                }
            }
        }
        // Completed with nothing readable under it — trust the order.
        return PaymentStatus.SUCCEEDED;
    }

    static PaymentStatus mapCaptureStatus(String captureStatus) {
        if (captureStatus == null) {
            return null;
        }
        return switch (captureStatus) {
            case "COMPLETED" -> PaymentStatus.SUCCEEDED;
            case "DECLINED", "FAILED" -> PaymentStatus.FAILED;
            case "PENDING" -> PaymentStatus.PROCESSING;
            case "REFUNDED" -> PaymentStatus.REFUNDED;
            default -> null;
        };
    }

    static PaymentStatus mapCaptureEventType(String eventType) {
        if (eventType == null) {
            return null;
        }
        return switch (eventType) {
            case "PAYMENT.CAPTURE.COMPLETED" -> PaymentStatus.SUCCEEDED;
            case "PAYMENT.CAPTURE.DENIED" -> PaymentStatus.FAILED;
            case "PAYMENT.CAPTURE.PENDING" -> PaymentStatus.PROCESSING;
            default -> null;
        };
    }

    static RefundStatus mapRefundEventType(String eventType) {
        if (eventType == null) {
            return null;
        }
        return switch (eventType) {
            // REVERSED is a refund PayPal forced, but the money went back either way.
            case "PAYMENT.CAPTURE.REFUNDED", "PAYMENT.CAPTURE.REVERSED" -> RefundStatus.SUCCEEDED;
            default -> null;
        };
    }

    private ProviderRefund toProviderRefund(JsonNode refund) {
        String rawStatus = text(refund, "status");
        return new ProviderRefund(text(refund, "id"), mapRefundStatus(rawStatus), rawStatus);
    }

    static RefundStatus mapRefundStatus(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "COMPLETED" -> RefundStatus.SUCCEEDED;
            case "CANCELLED", "FAILED" -> RefundStatus.FAILED;
            case "PENDING" -> RefundStatus.PENDING;
            default -> null;
        };
    }

    // ── HTTP plumbing ───────────────────────────────────────────────────

    /**
     * A bearer token, cached until shortly before it expires.
     *
     * <p>PayPal's tokens last hours, so this is not on the hot path in practice — but it
     * is fetched lazily rather than at startup so a PayPal outage cannot stop the
     * service booting.
     */
    private Mono<String> accessToken() {
        CachedToken current = cachedToken;
        if (current != null && current.isFresh()) {
            return Mono.just(current.value());
        }
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            return Mono.error(new PaymentProviderException(name(),
                    "PayPal credentials are not configured", null));
        }
        String basic = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        return http.post()
                .uri("/v1/oauth2/token")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("grant_type", "client_credentials"))
                .exchangeToMono(response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(raw -> {
                            if (response.statusCode().isError()) {
                                return Mono.error(new PaymentProviderException(name(),
                                        "PayPal token request failed (" + response.statusCode() + "): " + raw,
                                        null));
                            }
                            return Mono.just(raw);
                        }))
                .map(raw -> {
                    JsonNode node = MAPPER.readTree(raw);
                    String value = text(node, "access_token");
                    long expiresIn = node.path("expires_in").asLong(3600);
                    CachedToken token = new CachedToken(value, Instant.now().plusSeconds(expiresIn));
                    cachedToken = token;
                    log.debug("Obtained PayPal access token, valid {}s", expiresIn);
                    return value;
                });
    }

    private Mono<JsonNode> get(String path, String token) {
        return rawGet(path, token).onErrorResume(PayPalApiException.class,
                e -> retryOnceOnUnauthorized(e, fresh -> rawGet(path, fresh)));
    }

    private Mono<JsonNode> post(String path, String token, Object body, Map<String, String> extraHeaders) {
        return rawPost(path, token, body, extraHeaders).onErrorResume(PayPalApiException.class,
                e -> retryOnceOnUnauthorized(e, fresh -> rawPost(path, fresh, body, extraHeaders)));
    }

    private Mono<JsonNode> rawGet(String path, String token) {
        return http.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchangeToMono(r -> handle(r.statusCode(), r.bodyToMono(String.class).defaultIfEmpty(""), path));
    }

    private Mono<JsonNode> rawPost(String path, String token, Object body, Map<String, String> extraHeaders) {
        var spec = http.post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
        extraHeaders.forEach(spec::header);
        return spec.bodyValue(serialize(body))
                .exchangeToMono(r -> handle(r.statusCode(), r.bodyToMono(String.class).defaultIfEmpty(""), path));
    }

    /**
     * Exactly one re-auth and retry on a 401 — never a loop. A token can expire between
     * the cache check and the call, which is worth surviving; a second 401 means the
     * credentials are wrong, and retrying that forever would hammer PayPal with bad auth.
     *
     * <p>The retry runs against a freshly fetched token, not the stale one that just
     * failed — the cache is cleared first so {@code accessToken()} cannot hand it back.
     */
    private Mono<JsonNode> retryOnceOnUnauthorized(PayPalApiException e,
                                                   java.util.function.Function<String, Mono<JsonNode>> retry) {
        if (!e.isUnauthorized()) {
            return Mono.error(e);
        }
        log.info("PayPal returned 401 — refreshing the token and retrying once");
        cachedToken = null;
        return accessToken().flatMap(retry);
    }

    private Mono<JsonNode> handle(HttpStatusCode status, Mono<String> bodyMono, String path) {
        return bodyMono.flatMap(raw -> {
            if (status.isError()) {
                return Mono.error(new PayPalApiException(status, raw, path));
            }
            if (raw.isBlank()) {
                return Mono.just(MAPPER.createObjectNode());
            }
            try {
                return Mono.just(MAPPER.readTree(raw));
            } catch (Exception e) {
                return Mono.error(new PaymentProviderException(name(),
                        "PayPal returned an unreadable body from " + path, e));
            }
        });
    }

    private static String serialize(Object body) {
        return MAPPER.writeValueAsString(body);
    }

    /** Amount as PayPal wants it: a two-decimal string, from the validated minor units. */
    private static String decimalString(Money amount) {
        return BigDecimal.valueOf(amount.minorUnits(), 2).toPlainString();
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        return headers.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * Don't re-wrap what is already ours. {@link WebhookVerificationException} in
     * particular must reach {@code WebhookHandler} intact — wrapping it would turn a
     * 400 into a 500.
     */
    private static java.util.function.Predicate<Throwable> notAlreadyWrapped() {
        return e -> !(e instanceof PaymentProviderException) && !(e instanceof WebhookVerificationException);
    }

    private PaymentProviderException wrap(String what, Throwable e) {
        return new PaymentProviderException(name(), "PayPal failed to " + what + ": " + e.getMessage(), e);
    }

    /** A non-2xx from PayPal, kept typed so the capture path can inspect the issue code. */
    private static class PayPalApiException extends PaymentProviderException {

        private final HttpStatusCode status;
        private final String body;

        PayPalApiException(HttpStatusCode status, String body, String path) {
            super("paypal", "PayPal " + path + " returned " + status + ": " + body, null);
            this.status = status;
            this.body = body == null ? "" : body;
        }

        boolean isUnauthorized() {
            return status.value() == 401;
        }

        /** PayPal reports the specific problem in {@code details[].issue}. */
        boolean hasIssue(String issue) {
            return body.contains(issue);
        }
    }
}
