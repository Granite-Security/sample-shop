package org.granitesecurity.payment.provider.stripe;

import com.stripe.exception.IdempotencyException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentListParams;
import com.stripe.param.PaymentIntentSearchParams;
import com.stripe.param.RefundCreateParams;
import org.granitesecurity.payment.domain.PaymentStatus;
import org.granitesecurity.payment.domain.RefundStatus;
import org.granitesecurity.payment.provider.ConfirmationMode;
import org.granitesecurity.payment.provider.CreateIntentRequest;
import org.granitesecurity.payment.provider.Money;
import org.granitesecurity.payment.provider.PaymentProvider;
import org.granitesecurity.payment.provider.PaymentProviderException;
import org.granitesecurity.payment.provider.ProviderHealth;
import org.granitesecurity.payment.provider.ProviderIntent;
import org.granitesecurity.payment.provider.ProviderRefund;
import org.granitesecurity.payment.provider.ProviderWebhookEvent;
import org.granitesecurity.payment.provider.WebhookVerificationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Stripe behind the {@link PaymentProvider} port.
 *
 * <p>Everything Stripe-shaped that used to sit inline in {@code PaymentService} and
 * {@code WebhookHandler} lives here: the SDK calls, both status mappings, the
 * idempotency-collision recovery, and webhook signature verification. The logic was
 * moved, not rewritten — behaviour is meant to be identical to before the seam.
 *
 * <p>The API key is set globally by {@code StripeConfig} via {@code Stripe.apiKey},
 * which is why no client is injected here.
 */
@Component
@ConditionalOnProperty(name = "payment.providers.stripe.enabled", havingValue = "true", matchIfMissing = true)
public class StripePaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Two-decimal subset of Stripe's settlement currencies that the shop allows.
     * MDL is deliberately absent — Stripe does not settle it (refactor plan §1).
     */
    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("USD", "EUR", "RON", "CHF");

    @Value("${stripe.secret-key:}")
    private String secretKey;

    @Value("${stripe.webhook-secret:}")
    private String webhookSecret;

    @Value("${payment.providers.stripe.webhook.enabled:false}")
    private boolean webhookEnabled;

    @Override
    public String name() {
        return "stripe";
    }

    @Override
    public String displayName() {
        return "Card (Stripe)";
    }

    /**
     * False by default: no webhook has ever been registered for this deployment and
     * {@code /sync} is what confirms payments. Flipping this to true without registering
     * the endpoint in the Stripe dashboard changes nothing; registering it without
     * flipping this makes payment reject the deliveries.
     */
    @Override
    public boolean webhookEnabled() {
        return webhookEnabled;
    }

    @Override
    public ConfirmationMode confirmationMode() {
        return ConfirmationMode.CLIENT_SDK;
    }

    @Override
    public Set<String> supportedCurrencies() {
        return SUPPORTED_CURRENCIES;
    }

    @Override
    public Mono<ProviderHealth> health() {
        return Mono.fromCallable(() -> {
                    var params = PaymentIntentListParams.builder().setLimit(1L).build();
                    PaymentIntent.list(params);   // the probe: does the API answer at all
                    return ProviderHealth.up(Map.of(
                            "stripe", "connected",
                            "mode", mode()));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(StripeException.class, e -> {
                    log.warn("Stripe health check failed: {}", e.getMessage());
                    return Mono.just(ProviderHealth.down(e.getMessage()));
                });
    }

    @Override
    public Mono<ProviderIntent> createIntent(CreateIntentRequest request) {
        var params = intentParams(request).build();
        var options = RequestOptions.builder().setIdempotencyKey(request.idempotencyKey()).build();

        return Mono.fromCallable(() -> PaymentIntent.create(params, options))
                .subscribeOn(Schedulers.boundedElastic())
                // A collided key means we already created this intent and lost the response.
                // Recovering it is Stripe-specific, so it stays behind the port.
                .onErrorResume(IdempotencyException.class, e -> {
                    log.warn("Idempotency key collision for order {}, searching for existing PaymentIntent",
                            request.orderId());
                    return Mono.fromCallable(() -> {
                        var searchParams = PaymentIntentSearchParams.builder()
                                .setQuery("metadata['order_id']:'" + request.orderId() + "'")
                                .setLimit(1L)
                                .build();
                        return PaymentIntent.search(searchParams).getData().stream()
                                .findFirst()
                                .orElseThrow(() -> e);
                    }).subscribeOn(Schedulers.boundedElastic());
                })
                .map(this::toProviderIntent)
                .onErrorMap(StripeException.class, e -> wrap("create intent for order " + request.orderId(), e));
    }

    /**
     * Stripe needs no explicit void of the previous attempt — an abandoned PaymentIntent
     * simply never confirms — so this is a fresh create under a unique key. A provider
     * that does need the prior attempt cancelled would do it here.
     */
    @Override
    public Mono<ProviderIntent> recreateIntent(CreateIntentRequest request, String previousProviderPaymentId) {
        var params = intentParams(request).build();
        var options = RequestOptions.builder().setIdempotencyKey(request.idempotencyKey()).build();

        return Mono.fromCallable(() -> PaymentIntent.create(params, options))
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::toProviderIntent)
                .doOnSuccess(intent -> log.info("Recreated Stripe PaymentIntent {} for order {} (previous {})",
                        intent.providerPaymentId(), request.orderId(), previousProviderPaymentId))
                .onErrorMap(StripeException.class, e -> wrap("recreate intent for order " + request.orderId(), e));
    }

    @Override
    public Mono<ProviderIntent> retrieveIntent(String providerPaymentId) {
        return Mono.fromCallable(() -> PaymentIntent.retrieve(providerPaymentId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::toProviderIntent)
                .onErrorMap(StripeException.class, e -> wrap("retrieve intent " + providerPaymentId, e));
    }

    @Override
    public Mono<ProviderRefund> createRefund(String providerPaymentId, Money amount, String idempotencyKey) {
        var params = RefundCreateParams.builder()
                .setPaymentIntent(providerPaymentId)
                .setAmount(amount.minorUnits())
                .build();
        var options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();

        return Mono.fromCallable(() -> com.stripe.model.Refund.create(params, options))
                .subscribeOn(Schedulers.boundedElastic())
                // create returns a refund that is already succeeded or pending; a
                // rejected refund arrives as a StripeException, which the caller maps
                // to FAILED exactly as it did before the seam.
                .map(r -> new ProviderRefund(r.getId(), mapRefundStatus(r.getStatus()), r.getStatus()))
                .onErrorMap(StripeException.class, e -> wrap("create refund for " + providerPaymentId, e));
    }

    @Override
    public Mono<ProviderRefund> retrieveRefund(String providerRefundId) {
        return Mono.fromCallable(() -> com.stripe.model.Refund.retrieve(providerRefundId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(r -> new ProviderRefund(r.getId(), mapRefundStatus(r.getStatus()), r.getStatus()))
                .onErrorMap(StripeException.class, e -> wrap("retrieve refund " + providerRefundId, e));
    }

    @Override
    public ProviderWebhookEvent parseWebhook(String payload, Map<String, String> headers)
            throws WebhookVerificationException {
        String sigHeader = header(headers, "Stripe-Signature");
        if (sigHeader == null || sigHeader.isBlank()) {
            throw new WebhookVerificationException("Missing Stripe-Signature header");
        }
        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            throw new WebhookVerificationException("Invalid signature", e);
        }

        PaymentStatus status = mapEventType(event.getType());
        String providerPaymentId = null;
        Long orderId = null;

        var objectOpt = event.getDataObjectDeserializer().getObject();
        if (objectOpt.isPresent() && objectOpt.get() instanceof PaymentIntent intent) {
            providerPaymentId = intent.getId();
            orderId = parseOrderId(intent.getMetadata() == null ? null : intent.getMetadata().get("order_id"),
                    intent.getId());
        } else if (status != null) {
            // The SDK deserializer fails when the event was produced by a different API
            // version than the SDK expects. Falling back to raw JSON is the difference
            // between reconciling the payment and dropping it.
            log.warn("SDK deserializer failed for event {} ({}), falling back to manual JSON parsing",
                    event.getId(), event.getType());
            try {
                var dataObj = MAPPER.readTree(payload).get("data").get("object");
                providerPaymentId = dataObj.get("id").asText();
                var meta = dataObj.get("metadata");
                orderId = parseOrderId(
                        meta != null && meta.get("order_id") != null ? meta.get("order_id").asText(null) : null,
                        providerPaymentId);
            } catch (Exception e) {
                log.error("Manual JSON parsing failed for event {}: {}", event.getId(), e.getMessage());
            }
        }

        return new ProviderWebhookEvent(event.getId(), event.getType(), orderId, status, providerPaymentId);
    }

    private PaymentIntentCreateParams.Builder intentParams(CreateIntentRequest request) {
        var builder = PaymentIntentCreateParams.builder()
                .setAmount(request.amount().minorUnits())
                .setCurrency(request.amount().currencyLowerCase())
                .setAutomaticPaymentMethods(
                        PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                .setEnabled(true)
                                .build())
                .putMetadata("order_id", String.valueOf(request.orderId()));
        // Conditional because the two callers differed before the seam: create always
        // sent the key (empty string when unknown), retry never sent it. Callers keep
        // that distinction by passing "" or null, so live Stripe metadata is unchanged.
        if (request.username() != null) {
            builder.putMetadata("username", request.username());
        }
        return builder;
    }

    private ProviderIntent toProviderIntent(PaymentIntent intent) {
        Map<String, Object> payload = new HashMap<>();
        if (intent.getClientSecret() != null) {
            payload.put("clientSecret", intent.getClientSecret());
        }
        String declineReason = intent.getLastPaymentError() != null
                ? intent.getLastPaymentError().getMessage()
                : null;
        return new ProviderIntent(
                intent.getId(),
                mapIntentStatus(intent.getStatus()),
                intent.getStatus(),
                payload,
                null,               // CLIENT_SDK: nowhere to redirect
                declineReason);
    }

    private Long parseOrderId(String raw, String intentId) {
        if (raw == null || raw.isBlank()) {
            log.warn("PaymentIntent {} has no order_id metadata", intentId);
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            log.warn("PaymentIntent {} has non-numeric order_id metadata: {}", intentId, raw);
            return null;
        }
    }

    /**
     * Test or live, read from the API key prefix — the only thing that actually decides
     * it. The previous implementation inferred "live" from the mere existence of a
     * PaymentIntent, so a test account reported "live" as soon as it had one charge:
     * the health endpoint said live while taking test-mode money.
     */
    private String mode() {
        String key = secretKey == null ? "" : secretKey;
        if (key.startsWith("sk_test_") || key.startsWith("rk_test_")) return "test";
        if (key.startsWith("sk_live_") || key.startsWith("rk_live_")) return "live";
        return "unknown";
    }

    private PaymentProviderException wrap(String what, StripeException e) {
        return new PaymentProviderException(name(), "Stripe failed to " + what + ": " + e.getMessage(), e);
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

    /** Null means "no transition" — the caller leaves the stored status alone. */
    static PaymentStatus mapIntentStatus(String stripeStatus) {
        if (stripeStatus == null) {
            return null;
        }
        return switch (stripeStatus) {
            case "succeeded" -> PaymentStatus.SUCCEEDED;
            case "canceled" -> PaymentStatus.CANCELED;
            case "processing" -> PaymentStatus.PROCESSING;
            default -> null;
        };
    }

    static PaymentStatus mapEventType(String eventType) {
        if (eventType == null) {
            return null;
        }
        return switch (eventType) {
            case "payment_intent.succeeded" -> PaymentStatus.SUCCEEDED;
            case "payment_intent.payment_failed" -> PaymentStatus.FAILED;
            case "payment_intent.canceled" -> PaymentStatus.CANCELED;
            default -> null;
        };
    }

    static RefundStatus mapRefundStatus(String stripeStatus) {
        if (stripeStatus == null) {
            return null;
        }
        return switch (stripeStatus) {
            case "succeeded" -> RefundStatus.SUCCEEDED;
            case "failed", "canceled" -> RefundStatus.FAILED;
            case "pending" -> RefundStatus.PENDING;
            default -> null;
        };
    }
}
