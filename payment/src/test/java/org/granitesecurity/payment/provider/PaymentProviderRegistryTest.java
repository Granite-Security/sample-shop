package org.granitesecurity.payment.provider;

import org.granitesecurity.payment.provider.stripe.StripePaymentProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentProviderRegistryTest {

    private static PaymentProviderRegistry registry(List<PaymentProvider> providers, String currency) {
        return new PaymentProviderRegistry(providers, currency);
    }

    @Test
    void resolvesByNameCaseAndWhitespaceInsensitively() {
        var registry = registry(List.of(new StripePaymentProvider()), "CHF");

        assertThat(registry.get("stripe").name()).isEqualTo("stripe");
        assertThat(registry.get("STRIPE").name()).isEqualTo("stripe");
        assertThat(registry.get(" stripe ").name()).isEqualTo("stripe");
    }

    @Test
    void unknownProviderNamesTheEnabledOnes() {
        var registry = registry(List.of(new StripePaymentProvider()), "CHF");

        assertThatThrownBy(() -> registry.get("paypal"))
                .isInstanceOf(PaymentProviderRegistry.UnknownProviderException.class)
                .hasMessageContaining("paypal")
                .hasMessageContaining("stripe");
        assertThatThrownBy(() -> registry.get(null))
                .isInstanceOf(PaymentProviderRegistry.UnknownProviderException.class);
        assertThat(registry.has("paypal")).isFalse();
        assertThat(registry.has(null)).isFalse();
    }

    @Test
    void dispatchesCorrectlyWithMoreThanOneProvider() {
        var registry = registry(List.of(new StripePaymentProvider(), new NoopPaymentProvider()), "CHF");

        assertThat(registry.enabled()).hasSize(2);
        assertThat(registry.get("stripe").confirmationMode()).isEqualTo(ConfirmationMode.CLIENT_SDK);
        assertThat(registry.get("noop").confirmationMode()).isEqualTo(ConfirmationMode.REDIRECT);
    }

    @Test
    void defaultProviderOnlyExistsWhileExactlyOneIsEnabled() {
        assertThat(registry(List.of(new StripePaymentProvider()), "CHF").defaultProvider().name())
                .isEqualTo("stripe");

        var two = registry(List.of(new StripePaymentProvider(), new NoopPaymentProvider()), "CHF");
        assertThatThrownBy(two::defaultProvider)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 are enabled");
    }

    @Test
    void duplicateNamesFailFast() {
        assertThatThrownBy(() -> registry(
                List.of(new NoopPaymentProvider("dup", false), new NoopPaymentProvider("dup", true)), "CHF"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim the name 'dup'");
    }

    // --- startup validation ---

    @Test
    void rejectsAShopCurrencyOutsideTheClosedSet() {
        // MDL is deliberately out of scope: Stripe does not settle it.
        assertThatThrownBy(() -> registry(List.of(new StripePaymentProvider()), "MDL").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MDL");
    }

    @Test
    void rejectsAProviderThatCannotChargeTheShopCurrency() {
        PaymentProvider eurOnly = new NoopPaymentProvider() {
            @Override
            public Set<String> supportedCurrencies() {
                return Set.of("EUR");
            }
        };
        assertThatThrownBy(() -> registry(List.of(eurOnly), "CHF").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot charge CHF");
    }

    @Test
    void rejectsHavingNoProviderAtAll() {
        assertThatThrownBy(() -> registry(List.of(), "CHF").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No payment provider is enabled");
    }

    @Test
    void acceptsAValidConfiguration() {
        var registry = registry(List.of(new StripePaymentProvider()), "chf");
        registry.validate();
        assertThat(registry.shopCurrency()).isEqualTo("CHF");
    }
}
