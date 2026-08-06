package org.granitesecurity.payment.provider;

import org.granitesecurity.payment.domain.PaymentStatus;
import org.granitesecurity.payment.domain.RefundStatus;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A second provider that exists only in test sources.
 *
 * <p>It proves the registry and the service dispatch correctly when more than one
 * provider is enabled, before a real second adapter exists. Deliberately <b>not</b>
 * a Spring bean behind a dev profile: deployed config then differs from tested
 * config, and the profile becomes a way to accidentally ship a fake provider.
 *
 * <p>REDIRECT-shaped on purpose — the confirmation mode Stripe does not exercise.
 */
public class NoopPaymentProvider implements PaymentProvider {

    private final String name;
    private final boolean webhookEnabled;

    public NoopPaymentProvider() {
        this("noop", false);
    }

    public NoopPaymentProvider(String name, boolean webhookEnabled) {
        this.name = name;
        this.webhookEnabled = webhookEnabled;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String displayName() {
        return "No-op (test)";
    }

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
        return Set.of("USD", "EUR", "CHF");
    }

    @Override
    public Mono<ProviderHealth> health() {
        return Mono.just(ProviderHealth.up(Map.of("noop", "always up")));
    }

    @Override
    public Mono<ProviderIntent> createIntent(CreateIntentRequest request) {
        String id = "noop_" + UUID.randomUUID();
        return Mono.just(new ProviderIntent(id, null, "created", Map.of(), "https://example.invalid/pay/" + id, null));
    }

    @Override
    public Mono<ProviderIntent> recreateIntent(CreateIntentRequest request, String previousProviderPaymentId) {
        return createIntent(request);
    }

    @Override
    public Mono<ProviderIntent> retrieveIntent(String providerPaymentId) {
        return Mono.just(new ProviderIntent(providerPaymentId, PaymentStatus.SUCCEEDED, "succeeded",
                Map.of(), null, null));
    }

    @Override
    public Mono<ProviderRefund> createRefund(String providerPaymentId, Money amount, String idempotencyKey) {
        return Mono.just(new ProviderRefund("noopre_" + UUID.randomUUID(), RefundStatus.SUCCEEDED, "succeeded"));
    }

    @Override
    public Mono<ProviderRefund> retrieveRefund(String providerRefundId) {
        return Mono.just(new ProviderRefund(providerRefundId, RefundStatus.SUCCEEDED, "succeeded"));
    }

    @Override
    public Mono<ProviderWebhookEvent> parseWebhook(String payload, Map<String, String> headers) {
        return Mono.just(ProviderWebhookEvent.payment("evt_noop", "noop.event", null, null, null, null));
    }
}
