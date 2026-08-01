package org.granitesecurity.payment.provider;

import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;

/**
 * The seam between payment's business logic and a payment provider's SDK.
 *
 * <p>No provider SDK type crosses this boundary in either direction: implementations
 * translate to and from the value types in this package, so {@code PaymentService}
 * never learns which provider it is talking to beyond the name on the payment row.
 *
 * <p>Implementations are resolved <b>per payment, from {@code Payment.provider}</b>
 * via {@link PaymentProviderRegistry} — never from static config. A payment created
 * against one provider must keep being reconciled against that provider even after
 * the configured default changes.
 */
public interface PaymentProvider {

    /** Stable identifier, persisted in {@code payment.provider}. Lowercase. */
    String name();

    /** Shown in the provider selector at checkout. */
    String displayName();

    /**
     * Whether a webhook is registered provider-side. False means the only thing
     * confirming payments is {@code /sync}, which is the case for Stripe in this
     * deployment today — an explicit switch rather than an accident.
     */
    boolean webhookEnabled();

    /** How the frontend completes the payment. The UI switches on this, not on {@link #name()}. */
    ConfirmationMode confirmationMode();

    /** ISO 4217 codes this provider can charge, validated against the shop currency at startup. */
    Set<String> supportedCurrencies();

    Mono<ProviderHealth> health();

    Mono<ProviderIntent> createIntent(CreateIntentRequest request);

    /**
     * Builds a fresh intent for an order that already has a failed or abandoned one.
     * Distinct from {@link #createIntent} because idempotency semantics differ and
     * some providers must void the prior attempt first.
     */
    Mono<ProviderIntent> recreateIntent(CreateIntentRequest request, String previousProviderPaymentId);

    /** Backs {@code /sync}. Required — this is what confirms payments when no webhook exists. */
    Mono<ProviderIntent> retrieveIntent(String providerPaymentId);

    Mono<ProviderRefund> createRefund(String providerPaymentId, Money amount, String idempotencyKey);

    Mono<ProviderRefund> retrieveRefund(String providerRefundId);

    /**
     * Verifies and translates an inbound webhook. Only called when {@link #webhookEnabled()}.
     *
     * @throws WebhookVerificationException if the signature is missing or invalid
     */
    ProviderWebhookEvent parseWebhook(String payload, Map<String, String> headers)
            throws WebhookVerificationException;
}
