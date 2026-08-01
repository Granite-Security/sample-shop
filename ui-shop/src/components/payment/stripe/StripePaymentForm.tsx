import { useState } from 'react';
import { PaymentElement, useStripe, useElements } from '@stripe/react-stripe-js';

export default function PaymentForm({
  orderId,
  onPaymentConfirmed,
  onError,
}: {
  orderId: number;
  onPaymentConfirmed: () => void;
  onError: (msg: string) => void;
}) {
  const stripe = useStripe();
  const elements = useElements();
  const [confirming, setConfirming] = useState(false);
  const [elementReady, setElementReady] = useState(false);

  const handlePay = async () => {
    if (!stripe || !elements) return;
    setConfirming(true);
    try {
      const { error } = await stripe.confirmPayment({
        elements,
        confirmParams: { return_url: window.location.origin + `/orders/${orderId}` },
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
    <div className="payment-form">
      <PaymentElement onReady={() => setElementReady(true)} />
      <button
        className="btn btn-primary"
        style={{ marginTop: 16, width: '100%' }}
        disabled={!stripe || !elementReady || confirming}
        onClick={handlePay}
      >
        {!stripe || !elementReady ? 'Loading payment form…' : confirming ? 'Processing…' : 'Pay Now'}
      </button>
    </div>
  );
}
