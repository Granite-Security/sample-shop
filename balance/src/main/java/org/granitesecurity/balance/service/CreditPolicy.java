package org.granitesecurity.balance.service;

/**
 * Whether balance will fund a payment — the whole lending decision, in one place
 * (docs/finance/finance.md §4.2).
 *
 * <p>Balance decides, exactly as a card issuer decides. Checkout never inspects a
 * balance to work out what to offer: it cannot do so correctly, because funds move
 * between page load and capture, and a decline is an ordinary failed payment that
 * the existing PAYMENT_FAILED → retry path already handles.
 *
 * <p>The policy is expressed as a <em>minimum balance before the debit</em> so it can
 * be pushed into the conditional UPDATE that performs the debit. Evaluating it in
 * Java against a previously-read balance would reintroduce the race the single
 * statement exists to remove.
 */
public interface CreditPolicy {

    /**
     * @param amountMinor what the caller wants to spend, in rappen
     * @return the balance the account must have <em>before</em> the debit for it to
     *         be allowed
     */
    long minimumBalanceBefore(long amountMinor);
}
