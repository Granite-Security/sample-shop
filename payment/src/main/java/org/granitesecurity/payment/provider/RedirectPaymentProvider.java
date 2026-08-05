package org.granitesecurity.payment.provider;

import reactor.core.publisher.Mono;

/**
 * A provider the shopper leaves the site for, and that needs an explicit step to take
 * the money once they come back.
 *
 * <p>Separate from {@link PaymentProvider} because finalization is not universal:
 * Stripe's PaymentIntent moves to {@code succeeded} on its own and there is nothing to
 * call. PayPal's Orders v2 has two objects and a verb — an order reaches {@code APPROVED}
 * with <b>no money moved</b>, and only {@code POST /capture} charges it. Putting
 * {@code finalizePayment} on the main port would force every future adapter to implement
 * a no-op.
 *
 * <p>{@code WebhookHandler} and the return handler check for this with {@code instanceof}.
 */
public interface RedirectPaymentProvider extends PaymentProvider {

    /**
     * Takes the money for an approved payment, and reports where it ended up.
     *
     * <p><b>Must be idempotent.</b> The return endpoint and the webhook race on this
     * routinely — the shopper's browser and the provider's notification arrive in either
     * order, and often both. An already-finalized payment must resolve successfully by
     * re-reading current state, never fail and never charge twice.
     *
     * <p>Called only after the shopper has approved. A payment that is still awaiting
     * approval must come back with a null status ("no transition"), not an error.
     *
     * @param providerPaymentId the id stored in {@code payment.provider_payment_id}
     */
    Mono<ProviderIntent> finalizePayment(String providerPaymentId);
}
