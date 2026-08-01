package org.granitesecurity.payment.provider;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The enabled {@link PaymentProvider}s, keyed by name.
 *
 * <p>A provider is enabled by being a bean: each implementation carries its own
 * {@code @ConditionalOnProperty(payment.providers.<name>.enabled)}, so an
 * implemented-but-switched-off provider is a supported state.
 */
@Component
public class PaymentProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(PaymentProviderRegistry.class);

    /** The currencies the shop is allowed to price in — all two-decimal (see MinorUnits). */
    private static final Set<String> SUPPORTED_SHOP_CURRENCIES = Set.of("USD", "EUR", "RON", "CHF");

    private final Map<String, PaymentProvider> providers = new LinkedHashMap<>();
    private final String shopCurrency;

    public PaymentProviderRegistry(List<PaymentProvider> providers,
                                   @Value("${payment.shop-currency:${stripe.currency:chf}}") String shopCurrency) {
        this.shopCurrency = shopCurrency == null ? "" : shopCurrency.trim().toUpperCase(Locale.ROOT);
        for (PaymentProvider provider : providers) {
            PaymentProvider clash = this.providers.put(provider.name(), provider);
            if (clash != null) {
                throw new IllegalStateException(
                        "Two payment providers claim the name '" + provider.name() + "': "
                                + clash.getClass().getName() + " and " + provider.getClass().getName());
            }
        }
    }

    /**
     * Fails startup rather than the first order. A provider that cannot charge the shop
     * currency is a misconfiguration that would otherwise surface as a checkout error
     * for a real shopper, which is why {@code supportedCurrencies()} is checked here and
     * never again at runtime.
     */
    @PostConstruct
    void validate() {
        if (!SUPPORTED_SHOP_CURRENCIES.contains(shopCurrency)) {
            throw new IllegalStateException("Shop currency " + shopCurrency
                    + " is not one of " + SUPPORTED_SHOP_CURRENCIES);
        }
        if (providers.isEmpty()) {
            throw new IllegalStateException("No payment provider is enabled — payment cannot take money");
        }
        providers.values().forEach(p -> {
            if (!p.supportedCurrencies().contains(shopCurrency)) {
                throw new IllegalStateException("Provider '" + p.name() + "' cannot charge "
                        + shopCurrency + "; it supports " + p.supportedCurrencies());
            }
        });
        log.info("Payment providers enabled: {} (shop currency {})", providers.keySet(), shopCurrency);
    }

    /** @throws UnknownProviderException if nothing is registered under that name */
    public PaymentProvider get(String name) {
        PaymentProvider provider = name == null ? null : providers.get(name.trim().toLowerCase(Locale.ROOT));
        if (provider == null) {
            throw new UnknownProviderException(name, providers.keySet());
        }
        return provider;
    }

    public boolean has(String name) {
        return name != null && providers.containsKey(name.trim().toLowerCase(Locale.ROOT));
    }

    public List<PaymentProvider> enabled() {
        return List.copyOf(providers.values());
    }

    /**
     * The provider to use when the caller named none. Unambiguous while one provider is
     * enabled; once a second exists, the shopper picks and this becomes a 400 instead
     * (see the refactor plan §6).
     */
    public PaymentProvider defaultProvider() {
        if (providers.size() != 1) {
            throw new IllegalStateException(
                    "No provider specified and " + providers.size() + " are enabled: " + providers.keySet());
        }
        return providers.values().iterator().next();
    }

    public String shopCurrency() {
        return shopCurrency;
    }

    public static class UnknownProviderException extends RuntimeException {
        public UnknownProviderException(String name, Set<String> known) {
            super("Unknown payment provider '" + name + "'; enabled: " + known);
        }
    }
}
