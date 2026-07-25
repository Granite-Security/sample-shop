import { useEffect, useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router';
import { loadStripe } from '@stripe/stripe-js';
import { Elements } from '@stripe/react-stripe-js';
import { api } from '../api';
import PaymentForm from '../components/PaymentForm';

type Step = 'creating' | 'payment' | 'confirming' | 'error';

const stripePromise = loadStripe(window.__ENV__?.STRIPE_PUBLISHABLE_KEY ?? '');

export default function RetryPayment() {
  const { id } = useParams();
  const navigate = useNavigate();
  const orderId = Number(id);
  const [step, setStep] = useState<Step>('creating');
  const [clientSecret, setClientSecret] = useState<string | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!orderId) return;
    let cancelled = false;
    setStep('creating');
    setError('');
    api.payments.retryPaymentIntent(orderId)
      .then(payment => {
        if (cancelled) return;
        if (!payment.clientSecret) {
          setError('Payment provider did not return a client secret.');
          setStep('error');
          return;
        }
        setClientSecret(payment.clientSecret);
        setStep('payment');
      })
      .catch(e => {
        if (cancelled) return;
        setError(e instanceof Error ? e.message : 'Failed to start a new payment attempt');
        setStep('error');
      });
    return () => {
      cancelled = true;
    };
  }, [orderId]);

  const handlePaymentError = (msg: string) => {
    setError(msg);
    setStep('payment');
  };

  const handlePaymentConfirmed = async () => {
    setStep('confirming');
    try {
      await api.payments.syncPaymentIntent(orderId);
      navigate(`/orders/${orderId}`);
    } catch {
      setError('Payment was submitted, but we could not confirm its status. Check the order page in a moment.');
      setStep('error');
    }
  };

  if (!id || Number.isNaN(orderId)) {
    return <div className="page"><p>Order not found.</p></div>;
  }

  return (
    <div className="page checkout-page">
      <h1>Retry Payment</h1>
      <p style={{ marginBottom: 16 }}>Order #{orderId}</p>

      {step === 'creating' && (
        <div style={{ textAlign: 'center', padding: '2rem 0' }}>
          <div className="spinner" style={{ margin: '0 auto 1rem' }} />
          <p>Preparing a new payment attempt…</p>
        </div>
      )}

      {step === 'confirming' && (
        <div style={{ textAlign: 'center', padding: '2rem 0' }}>
          <div className="spinner" style={{ margin: '0 auto 1rem' }} />
          <p>Confirming payment…</p>
        </div>
      )}

      {step === 'error' && (
        <>
          <p className="error">{error}</p>
          <Link to={`/orders/${orderId}`} className="btn" style={{ marginTop: 16 }}>Back to Order</Link>
        </>
      )}

      {step === 'payment' && clientSecret && (
        <>
          {error && <p className="error">{error}</p>}
          <Elements key={clientSecret} stripe={stripePromise} options={{ clientSecret }}>
            <PaymentForm
              orderId={orderId}
              onPaymentConfirmed={handlePaymentConfirmed}
              onError={handlePaymentError}
            />
          </Elements>
        </>
      )}
    </div>
  );
}
