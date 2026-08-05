package org.granitesecurity.payment.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The PayPal HTTP client.
 *
 * <p>Deliberately unlike {@link StripeConfig}, which sets a <b>global</b>
 * {@code Stripe.apiKey}. Credentials here are instance state on a bean: a global is
 * exactly the thing that makes two providers interfere, and there are now two.
 *
 * <p>Only created when PayPal is enabled, so a deployment that never configured it
 * carries no half-initialised client.
 */
@Configuration
@ConditionalOnProperty(name = "payment.providers.paypal.enabled", havingValue = "true")
public class PayPalConfig {

    private static final Logger log = LoggerFactory.getLogger(PayPalConfig.class);

    private static final String SANDBOX_BASE_URL = "https://api-m.sandbox.paypal.com";
    private static final String LIVE_BASE_URL = "https://api-m.paypal.com";

    @Value("${paypal.env:sandbox}")
    private String env;

    /**
     * Base URL comes from an explicit {@code paypal.env}, never inferred from the
     * credentials. PayPal client ids carry no test/live marker the way Stripe's
     * {@code sk_test_} prefix does, so anything inferred would be a guess — and the
     * failure mode of guessing wrong is taking real money.
     */
    @Bean
    public WebClient payPalWebClient(WebClient.Builder builder) {
        String baseUrl = baseUrl();
        log.info("PayPal client configured for {} ({})", env, baseUrl);
        return builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private String baseUrl() {
        String mode = env == null ? "" : env.trim().toLowerCase();
        return switch (mode) {
            case "live", "production" -> LIVE_BASE_URL;
            case "sandbox", "" -> SANDBOX_BASE_URL;
            default -> throw new IllegalStateException(
                    "paypal.env must be 'sandbox' or 'live', got '" + env + "'");
        };
    }
}
