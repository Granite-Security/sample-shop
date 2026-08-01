import { useMemo } from 'react';
import { loadStripe } from '@stripe/stripe-js';
import { Elements } from '@stripe/react-stripe-js';
import type { ProviderPayload } from '../../../types';
import StripePaymentForm from './StripePaymentForm';

/**
 * Everything Stripe-specific in the checkout flow: the publishable key, the
 * `<Elements>` provider and the client secret it needs. Kept out of CheckoutPage
 * so that page never imports `@stripe/*`.
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
  const options = useMemo(() => (clientSecret ? { clientSecret } : undefined), [clientSecret]);

  if (!clientSecret) {
    // Not a loading state: the payment exists but carries nothing Stripe can
    // confirm with, so waiting would spin forever.
    return (
      <p className="border-l-2 border-terracotta bg-terracotta/10 px-4 py-3 text-sm text-terracotta">
        This payment is missing its Stripe client secret.
      </p>
    );
  }

  return (
    <Elements key={orderId} stripe={stripePromise} options={options}>
      <StripePaymentForm onPaymentConfirmed={onPaymentConfirmed} onError={onError} />
    </Elements>
  );
}
