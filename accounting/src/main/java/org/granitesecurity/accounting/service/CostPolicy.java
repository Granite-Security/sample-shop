package org.granitesecurity.accounting.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The cost assumptions of §2.8, in one place and in config so revising one is not a
 * migration (D21).
 *
 * <p><b>Every number here is a stated assumption, not a measurement.</b> That is the whole
 * reason they are together and named: a 3% fee and a CHF 1.00 shipping cost scattered
 * through posting rules would read like facts.
 */
@Component
public class CostPolicy {

    /** Stripe and PayPal: 3% of the amount plus a fixed fee, charged at capture (D29). */
    @Value("${accounting.costs.processor-fee-percent:3.0}")
    private BigDecimal processorFeePercent;

    @Value("${accounting.costs.processor-fee-fixed-minor:30}")
    private long processorFeeFixedMinor;

    /** CHF 1.00 per order, a cost of fulfilling that we do not charge for (D30). */
    @Value("${accounting.costs.shipping-minor:100}")
    private long shippingMinor;

    /**
     * Zero for a balance-funded order: no processor is involved, so there is no fee to
     * charge. That is a real business observation and it falls straight out of the posting
     * rules — paying from a platform balance is cheaper for us than a card (§2.8).
     */
    public long processorFee(String provider, long amountMinor) {
        if (provider == null || "balance".equalsIgnoreCase(provider) || amountMinor <= 0) {
            return 0L;
        }
        return BigDecimal.valueOf(amountMinor)
                .multiply(processorFeePercent)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                .longValueExact() + processorFeeFixedMinor;
    }

    public long shippingMinor() {
        return shippingMinor;
    }

    public String describe() {
        return "processor fee " + processorFeePercent + "% + " + processorFeeFixedMinor
                + " rappen; shipping " + shippingMinor + " rappen per order";
    }
}
