package org.granitesecurity.payment.provider;

/**
 * Everything a provider needs to open a payment, with nothing provider-specific in it.
 *
 * @param orderId        the shop order this pays for; providers put it in their metadata
 *                       so an inbound webhook can be traced back to a payment row
 * @param amount         amount and currency
 * @param username       may be null for orders placed without a session
 * @param idempotencyKey caller-chosen; retrying with the same key must not double-charge.
 *                       How an adapter recovers from a key collision is its own business.
 * @param returnUrl      where a REDIRECT provider sends the shopper on success; null for CLIENT_SDK
 * @param cancelUrl      where a REDIRECT provider sends the shopper on abandonment; null for CLIENT_SDK
 */
public record CreateIntentRequest(
        Long orderId,
        Money amount,
        String username,
        String idempotencyKey,
        String returnUrl,
        String cancelUrl) {

    public static CreateIntentRequest of(Long orderId, Money amount, String username, String idempotencyKey) {
        return new CreateIntentRequest(orderId, amount, username, idempotencyKey, null, null);
    }
}
