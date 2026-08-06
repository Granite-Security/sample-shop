import { useState } from 'react';
import { PaymentElement, useElements, useStripe } from '@stripe/react-stripe-js';

/**
 * The Stripe Elements form. Lifted out of CheckoutPage unchanged in behaviour —
 * only its location moved, so every `@stripe/*` import now lives under
 * components/payment/stripe/.
 */
export default function StripePaymentForm({
  returnUrl,
  onPaymentConfirmed,
  onError,
}: {
  /** Where Stripe sends the shopper after confirming — an order or the balance page. */
  returnUrl: string;
  onPaymentConfirmed: () => void;
  onError: (msg: string) => void;
}) {
  const stripe = useStripe();
  const elements = useElements();
  const [confirming, setConfirming] = useState(false);
  const [ready, setReady] = useState(false);

  const handlePay = async () => {
    if (!stripe || !elements) return;
    setConfirming(true);
    try {
      const { error } = await stripe.confirmPayment({
        elements,
        confirmParams: { return_url: returnUrl },
        redirect: 'if_required',
      });
      if (error) {
        onError(error.message ?? 'Payment failed');
        setConfirming(false);
      } else {
        onPaymentConfirmed();
      }
    } catch (e) {
      onError(e instanceof Error ? e.message : 'Payment failed');
      setConfirming(false);
    }
  };

  return (
    <div>
      <PaymentElement onReady={() => setReady(true)} />
      <button
        disabled={!stripe || !ready || confirming}
        onClick={handlePay}
        className="mt-6 w-full bg-cocoa py-4 text-xs uppercase tracking-[0.2em] text-ivory transition-colors duration-300 hover:bg-espresso disabled:cursor-not-allowed disabled:opacity-40"
      >
        {!stripe || !ready ? 'Loading payment form…' : confirming ? 'Processing…' : 'Pay Now'}
      </button>
    </div>
  );
}
