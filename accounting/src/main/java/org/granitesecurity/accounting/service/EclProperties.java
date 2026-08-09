package org.granitesecurity.accounting.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The IFRS 9 provision matrix (§2.6): ageing bands and a loss rate for each.
 *
 * <p>In config rather than in code because <b>these rates are assumptions, and until there
 * is repayment history they are invented ones</b>. Every figure derived from them carries
 * {@link #asOf} and is labelled an estimate (D21) — a provision matrix with invented rates
 * presented as a measured figure is worse than no provision at all.
 */
@Component
@ConfigurationProperties(prefix = "accounting.ecl")
public class EclProperties {

    /** The date these rates were set. Rendered beside every figure derived from them. */
    private LocalDate asOf = LocalDate.parse("2026-08-09");

    private List<Band> bands = new ArrayList<>();

    public LocalDate getAsOf() {
        return asOf;
    }

    public void setAsOf(LocalDate asOf) {
        this.asOf = asOf;
    }

    public List<Band> getBands() {
        return bands;
    }

    public void setBands(List<Band> bands) {
        this.bands = bands;
    }

    /** @param maxAgeDays null means the open-ended oldest band */
    public static class Band {
        private Integer maxAgeDays;
        private BigDecimal lossRate = BigDecimal.ZERO;

        public Integer getMaxAgeDays() {
            return maxAgeDays;
        }

        public void setMaxAgeDays(Integer maxAgeDays) {
            this.maxAgeDays = maxAgeDays;
        }

        public BigDecimal getLossRate() {
            return lossRate;
        }

        public void setLossRate(BigDecimal lossRate) {
            this.lossRate = lossRate;
        }

        public boolean covers(long ageDays) {
            return maxAgeDays == null || ageDays < maxAgeDays;
        }
    }
}
