package org.granitesecurity.shop.service;

import org.granitesecurity.shop.domain.Voucher;

import java.math.BigDecimal;

/**
 * What a voucher code is worth on a particular cart, or why it is worth nothing.
 *
 * <p>One type answers both the preview endpoint and placement, so the number a
 * shopper is shown and the number they are charged come out of the same code —
 * the same reason {@link PackagingPlan} serves the quote and the order.
 *
 * <p>A refusal is not an exception. Checkout has to render <em>why</em> a code was
 * rejected, so the reason is a value the handler can phrase; only placement turns
 * one into a 400.
 */
public record VoucherOutcome(Voucher voucher, BigDecimal discountTotal, Refusal refusal) {

    public static VoucherOutcome applied(Voucher voucher, BigDecimal discountTotal) {
        return new VoucherOutcome(voucher, discountTotal, null);
    }

    /**
     * No code was sent, which is most orders. Neither applied nor refused: there was
     * nothing to apply and nothing to refuse, and the discount is zero.
     */
    public static VoucherOutcome none() {
        return new VoucherOutcome(null, BigDecimal.ZERO.setScale(2), null);
    }

    public static VoucherOutcome refused(Refusal refusal) {
        return new VoucherOutcome(null, BigDecimal.ZERO.setScale(2), refusal);
    }

    /**
     * A voucher was found, priced and may be claimed. Deliberately not just
     * "no refusal": {@link #none()} has no refusal either, and a caller reading that
     * as applied would dereference a voucher that was never there.
     */
    public boolean applied() {
        return refusal == null && voucher != null;
    }

    public boolean refused() {
        return refusal != null;
    }

    public enum Refusal {
        NOT_FOUND("No such voucher code"),
        NOT_YET_VALID("This voucher is not valid yet"),
        EXPIRED("This voucher has expired"),
        REVOKED("This voucher is no longer available"),
        /**
         * Advisory when it comes from a preview, authoritative only when it comes from
         * the redemption insert — see {@code VoucherService.redeem}.
         */
        ALREADY_USED("You have already used this voucher"),
        BELOW_MINIMUM("This voucher would leave too little to charge; please remove an item or the code");

        private final String message;

        Refusal(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }
}
