import { useState } from 'react';
import type { ProviderPayload } from '../../types';

/**
 * The REDIRECT confirmation mode: the shopper leaves for the provider and returns.
 *
 * Provider-agnostic on purpose — this is why most non-card providers need only a
 * selector entry and a backend adapter, with no new component. Nothing renders it
 * yet; it exists so PaymentWidget's switch is total rather than a single-case stub
 * the next provider has to reopen.
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
    return (
      <p className="border-l-2 border-terracotta bg-terracotta/10 px-4 py-3 text-sm text-terracotta">
        {displayName} did not return a payment link.
      </p>
    );
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
    <div>
      <p className="mb-6 text-sm text-cocoa/70">
        You will be taken to {displayName} to complete this payment, then returned here.
      </p>
      <button
        disabled={leaving}
        onClick={handleContinue}
        className="w-full bg-cocoa py-4 text-xs uppercase tracking-[0.2em] text-ivory transition-colors duration-300 hover:bg-espresso disabled:cursor-not-allowed disabled:opacity-40"
      >
        {leaving ? 'Redirecting…' : `Continue to ${displayName}`}
      </button>
    </div>
  );
}
