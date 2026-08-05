package org.granitesecurity.payment.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the PayPal beans can actually be built.
 *
 * <p>This exists because they could not. {@code payPalWebClient} originally took an
 * injected {@code WebClient.Builder}, and there is no auto-configured bean of that type
 * in this service — so the first deploy with {@code paypal.enabled=true} failed startup
 * outright and crash-looped, taking Stripe down with it. Nothing caught it: the only
 * context test is {@code PaymentApplicationTests}, which needs Postgres and never runs
 * with PayPal enabled.
 *
 * <p>{@link ApplicationContextRunner} loads just this configuration, so it verifies the
 * wiring without a database, a broker, or a PayPal account.
 */
class PayPalConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PayPalConfig.class);

    @Test
    void buildsAWebClientWhenEnabled() {
        runner.withPropertyValues(
                        "payment.providers.paypal.enabled=true",
                        "paypal.env=sandbox")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(WebClient.class));
    }

    @Test
    void contributesNothingWhenDisabled() {
        // The default. A deployment that never configured PayPal must carry no
        // half-initialised client.
        runner.withPropertyValues("payment.providers.paypal.enabled=false")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(WebClient.class));
    }

    @Test
    void liveEnvIsAccepted() {
        runner.withPropertyValues(
                        "payment.providers.paypal.enabled=true",
                        "paypal.env=live")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(WebClient.class));
    }

    @Test
    void anUnrecognisedEnvFailsFastRatherThanGuessing() {
        // Guessing between sandbox and live is the one mistake that charges real money,
        // so a typo has to stop the boot instead of defaulting.
        runner.withPropertyValues(
                        "payment.providers.paypal.enabled=true",
                        "paypal.env=staging")
                .run(context -> assertThat(context).hasFailed());
    }
}
