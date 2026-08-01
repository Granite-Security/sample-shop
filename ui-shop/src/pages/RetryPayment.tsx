import { useEffect, useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router';
import { api } from '../api';
import PaymentWidget from '../components/payment/PaymentWidget';
import { usePaymentProviders } from '../components/payment/usePaymentProviders';
import type { ProviderPayload } from '../types';

type Step = 'creating' | 'payment' | 'confirming' | 'error';

export default function RetryPayment() {
  const { id } = useParams();
  const navigate = useNavigate();
  const orderId = Number(id);
  const [step, setStep] = useState<Step>('creating');
  const [provider, setProvider] = useState<string | null>(null);
  const [payload, setPayload] = useState<ProviderPayload | null>(null);
  const [error, setError] = useState('');
  const { find } = usePaymentProviders();

  useEffect(() => {
    if (!orderId) return;
    let cancelled = false;
    setStep('creating');
    setError('');
    api.payments.retryPaymentIntent(orderId)
      .then(payment => {
        if (cancelled) return;
        // Tolerate the legacy flat clientSecret: a payment row written before the
        // provider_payload migration still answers with only that field.
        const resolved: ProviderPayload = payment.providerPayload
          ?? (payment.clientSecret ? { clientSecret: payment.clientSecret } : {});
        if (Object.keys(resolved).length === 0) {
          setError('The payment provider returned nothing to complete the payment with.');
          setStep('error');
          return;
        }
        setProvider(payment.provider);
        setPayload(resolved);
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

      {step === 'payment' && provider && payload && (
        <>
          {error && <p className="error">{error}</p>}
          <PaymentWidget
            provider={provider}
            displayName={find(provider)?.displayName}
            confirmationMode={find(provider)?.confirmationMode ?? 'CLIENT_SDK'}
            payload={payload}
            orderId={orderId}
            onPaymentConfirmed={handlePaymentConfirmed}
            onError={handlePaymentError}
          />
        </>
      )}
    </div>
  );
}
