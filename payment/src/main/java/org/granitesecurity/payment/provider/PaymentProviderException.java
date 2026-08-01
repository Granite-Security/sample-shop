package org.granitesecurity.payment.provider;

/**
 * A call to a payment provider failed.
 *
 * <p>Adapters wrap their SDK's exception in this so callers can distinguish "the
 * provider rejected or could not be reached" from "our own persistence failed" without
 * importing the SDK. That distinction is load-bearing: the first marks a refund FAILED,
 * the second must propagate.
 */
public class PaymentProviderException extends RuntimeException {

    private final String provider;

    public PaymentProviderException(String provider, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
    }

    public String provider() {
        return provider;
    }
}
