package org.granitesecurity.payment.provider;

import org.granitesecurity.payment.domain.RefundStatus;

/**
 * @param providerRefundId the provider's id for the refund
 * @param status           mapped to our vocabulary; null when the provider reported a
 *                         status we deliberately do not act on
 * @param rawStatus        the provider's own string, for logs and diagnosis
 */
public record ProviderRefund(String providerRefundId, RefundStatus status, String rawStatus) {
}
