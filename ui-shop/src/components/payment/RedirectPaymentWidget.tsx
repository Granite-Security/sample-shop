import { useState } from 'react';
import type { ProviderPayload } from '../../types';

/**
 * The REDIRECT confirmation mode: the shopper leaves for the provider and comes
 * back to /api/payments/return/{provider}.
 *
 * <p>Provider-agnostic on purpose — this is why most non-card providers need only a
 * selector entry and a backend adapter, with no new component. Nothing in the app
 * renders it yet; it exists so PaymentWidget's switch is total rather than a
 * single-case stub that the next provider has to reopen.
 */
export default function RedirectPaymentWidget({
  payload,
  displayName,
  onError,
}: {
  payload: ProviderPayload;
  displayName: string;
  onError: (msg: string) => void;
}) {
  const [leaving, setLeaving] = useState(false);
  const redirectUrl = payload.redirectUrl;

  if (!redirectUrl) {
    return <p className="error">{displayName} did not return a payment link.</p>;
  }

  const handleContinue = () => {
    setLeaving(true);
    try {
      window.location.assign(redirectUrl);
    } catch (e) {
      setLeaving(false);
      onError(e instanceof Error ? e.message : `Could not open ${displayName}`);
    }
  };

  return (
    <div className="payment-form">
      <p style={{ marginBottom: 16 }}>
        You will be taken to {displayName} to complete this payment, then returned here.
      </p>
      <button
        className="btn btn-primary"
        style={{ width: '100%' }}
        disabled={leaving}
        onClick={handleContinue}
      >
        {leaving ? 'Redirecting…' : `Continue to ${displayName}`}
      </button>
    </div>
  );
}
