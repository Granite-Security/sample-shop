package org.granitesecurity.payment.provider.balance;

import org.granitesecurity.payment.domain.PaymentStatus;
import org.granitesecurity.payment.domain.RefundStatus;
import org.granitesecurity.payment.provider.ConfirmationMode;
import org.granitesecurity.payment.provider.CreateIntentRequest;
import org.granitesecurity.payment.provider.Money;
import org.granitesecurity.payment.provider.ProviderHealth;
import org.granitesecurity.payment.provider.ProviderIntent;
import org.granitesecurity.payment.provider.ProviderRefund;
import org.granitesecurity.payment.provider.ProviderWebhookEvent;
import org.granitesecurity.payment.provider.RedirectPaymentProvider;
import org.granitesecurity.payment.provider.WebhookVerificationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;

/**
 * The user's own CHF balance, offered at checkout alongside Stripe and PayPal.
 *
 * <p><b>Nothing here is special-cased anywhere else.</b> Balance implements the same
 * {@link RedirectPaymentProvider} port PayPal does, so the shop, the order lifecycle,
 * {@code /sync}, retry, refunds and the checkout UI all work unchanged
 * (docs/finance/finance.md §4.1).
 *
 * <p>PayPal's model maps onto it exactly: an order reaches APPROVED with no money
 * moved, and only capture charges it. For balance, creating an intent checks funds and
 * records the request; {@link #finalizePayment} writes the ledger rows. That is why
 * {@code CREATED} maps to a <em>null</em> status — no transition — and why an abandoned
 * checkout leaves no money moved and no hold to expire.
 *
 * <p>The redirect goes to our own gateway rather than an external site. One extra round
 * trip through {@code /api/payments/return/balance} buys complete uniformity with every
 * other provider, and {@code finalizePayment} has to be idempotent regardless because
 * the return and {@code /sync} race.
 */
@Component
@ConditionalOnProperty(name = "payment.providers.balance.enabled", havingValue = "true")
public class BalanceProvider implements RedirectPaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(BalanceProvider.class);

    /** The ledger is single-currency by design (docs/finance/finance.md D4). */
    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("CHF");

    private final BalanceClient balanceClient;

    public BalanceProvider(BalanceClient balanceClient) {
        this.balanceClient = balanceClient;
    }

    @Override
    public String name() {
        return "balance";
    }

    @Override
    public String displayName() {
        return "My Balance";
    }

    /**
     * There is no third party to call us back. Confirmation is the return endpoint,
     * with {@code /sync} as the reconciliation path — the same posture Stripe has in
     * this deployment.
     */
    @Override
    public boolean webhookEnabled() {
        return false;
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
        // Reading a non-existent intent proves balance is reachable and that our
        // internal token is accepted; a 404 is the healthy answer.
        return balanceClient.get("00000000-0000-0000-0000-000000000000")
                .map(v -> ProviderHealth.up(Map.of("balance", "connected")))
                .onErrorResume(e -> Mono.just(e.getMessage() != null && e.getMessage().contains("404")
                        ? ProviderHealth.up(Map.of("balance", "connected"))
                        : ProviderHealth.down(e.getMessage())));
    }

    @Override
    public Mono<ProviderIntent> createIntent(CreateIntentRequest request) {
        return balanceClient.createIntent(
                        request.username(), request.amount().minorUnits(), request.orderId())
                .map(view -> toIntent(view, request.returnUrl()))
                .doOnSuccess(i -> log.info("Balance intent {} created for order {}",
                        i.providerPaymentId(), request.orderId()));
    }

    /**
     * A retry is simply a new intent: the previous one moved no money unless it was
     * captured, and a captured one would not be retried.
     */
    @Override
    public Mono<ProviderIntent> recreateIntent(CreateIntentRequest request, String previousProviderPaymentId) {
        return createIntent(request);
    }

    @Override
    public Mono<ProviderIntent> retrieveIntent(String providerPaymentId) {
        return balanceClient.get(providerPaymentId).map(view -> toIntent(view, null));
    }

    /** Takes the money. Idempotent on balance's side: re-capturing returns current state. */
    @Override
    public Mono<ProviderIntent> finalizePayment(String providerPaymentId) {
        return balanceClient.capture(providerPaymentId)
                .map(view -> toIntent(view, null))
                .doOnSuccess(i -> log.info("Balance intent {} finalized: {}",
                        providerPaymentId, i.rawStatus()));
    }

    /**
     * Balance refunds the whole intent. A partial refund would need the ledger to
     * track remaining amounts per intent, which nothing asks for yet — so a partial
     * request is refused loudly rather than quietly refunding the full amount.
     */
    @Override
    public Mono<ProviderRefund> createRefund(String providerPaymentId, Money amount, String idempotencyKey) {
        return balanceClient.get(providerPaymentId)
                .flatMap(view -> {
                    if (amount != null && amount.minorUnits() != view.amountMinor()) {
                        return Mono.error(new org.granitesecurity.payment.provider.PaymentProviderException(
                                "balance",
                                "Partial refunds are not supported: asked for " + amount.minorUnits()
                                        + " of " + view.amountMinor() + " rappen", null));
                    }
                    return balanceClient.refund(providerPaymentId);
                })
                .map(view -> new ProviderRefund(
                        view.transferId() == null ? view.id() : view.transferId(),
                        "REFUNDED".equals(view.status()) ? RefundStatus.SUCCEEDED : RefundStatus.FAILED,
                        view.status()));
    }

    @Override
    public Mono<ProviderRefund> retrieveRefund(String providerRefundId) {
        // Refunds are not separately addressable: the intent carries the outcome.
        return balanceClient.get(providerRefundId)
                .map(view -> new ProviderRefund(
                        providerRefundId,
                        "REFUNDED".equals(view.status()) ? RefundStatus.SUCCEEDED : RefundStatus.PENDING,
                        view.status()));
    }

    @Override
    public Mono<ProviderWebhookEvent> parseWebhook(String payload, Map<String, String> headers) {
        // webhookEnabled() is false, so WebhookHandler never calls this. Signalling
        // rather than returning empty keeps a misconfiguration loud.
        return Mono.error(new WebhookVerificationException("balance has no webhooks"));
    }

    /**
     * Balance's vocabulary → ours. CREATED maps to <b>null</b>: no transition, because
     * nothing has moved yet and the payment row must keep its own CREATED.
     */
    private static ProviderIntent toIntent(BalanceClient.BalanceIntentView view, String returnUrl) {
        PaymentStatus status = switch (view.status()) {
            case "CAPTURED" -> PaymentStatus.SUCCEEDED;
            case "FAILED" -> PaymentStatus.FAILED;
            case "REFUNDED" -> PaymentStatus.REFUNDED;
            default -> null;
        };
        return new ProviderIntent(
                view.id(),
                status,
                view.status(),
                Map.of(),
                returnUrl,
                view.declineReason());
    }
}
