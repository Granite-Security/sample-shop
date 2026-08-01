import { useMemo } from 'react';
import { loadStripe } from '@stripe/stripe-js';
import { Elements } from '@stripe/react-stripe-js';
import type { ProviderPayload } from '../../../types';
import StripePaymentForm from './StripePaymentForm';

/**
 * Everything Stripe-specific in the checkout flow: the publishable key, the
 * `<Elements>` provider, and the client secret it needs.
 *
 * <p>Kept out of Checkout/RetryPayment so those pages never import `@stripe/*`.
 * A second CLIENT_SDK provider adds a sibling of this file and one case in
 * PaymentWidget — nothing in the pages changes.
 */
const stripePromise = loadStripe(window.__ENV__?.STRIPE_PUBLISHABLE_KEY ?? '');

export default function StripePaymentWidget({
  payload,
  orderId,
  onPaymentConfirmed,
  onError,
}: {
  payload: ProviderPayload;
  orderId: number;
  onPaymentConfirmed: () => void;
  onError: (msg: string) => void;
}) {
  const clientSecret = payload.clientSecret;
  const options = useMemo(
    () => (clientSecret ? { clientSecret } : undefined),
    [clientSecret],
  );

  if (!clientSecret) {
    // Not a loading state: the payment exists but carries nothing Stripe can
    // confirm with, so waiting would spin forever.
    return <p className="error">This payment is missing its Stripe client secret.</p>;
  }

  return (
    <Elements key={clientSecret} stripe={stripePromise} options={options}>
      <StripePaymentForm
        orderId={orderId}
        onPaymentConfirmed={onPaymentConfirmed}
        onError={onError}
      />
    </Elements>
  );
}
