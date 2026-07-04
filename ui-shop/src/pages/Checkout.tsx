import { useState, useEffect, useCallback, useRef } from 'react';
import { useNavigate, Link } from 'react-router';
import { loadStripe } from '@stripe/stripe-js';
import { Elements, PaymentElement, useStripe, useElements } from '@stripe/react-stripe-js';
import { useCart } from '../contexts/CartContext';
import { useAuth } from '../auth';
import { api } from '../api';
import type { OrderResponse } from '../types';

type Step = 'review' | 'placing' | 'waiting_payment' | 'payment' | 'confirming' | 'done' | 'failed' | 'error';

const stripePromise = loadStripe(import.meta.env.VITE_STRIPE_PUBLISHABLE_KEY);
const POLL_INTERVAL = 1000;
const POLL_TIMEOUT = 30000;
const MAX_RETRIES = 25;

function PaymentForm({
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
      <PaymentElement />
      <button
        className="btn btn-primary"
        style={{ marginTop: 16, width: '100%' }}
        disabled={!stripe || confirming}
        onClick={handlePay}
      >
        {!stripe ? 'Loading payment form…' : confirming ? 'Processing…' : 'Pay Now'}
      </button>
    </div>
  );
}

export default function Checkout() {
  const { items, total, clearCart } = useCart();
  const { isAuthenticated } = useAuth();
  const navigate = useNavigate();
  const [step, setStep] = useState<Step>('review');
  const [error, setError] = useState('');
  const [order, setOrder] = useState<OrderResponse | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const stopPolling = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  const pollForClientSecret = useCallback((orderId: number, startedAt: number) => {
    if (pollRef.current) return;
    let consecutiveErrors = 0;
    pollRef.current = setInterval(async () => {
      try {
        const payment = await api.getPaymentIntent(orderId);
        if (payment.clientSecret) {
          stopPolling();
          setOrder(prev => prev ? { ...prev, clientSecret: payment.clientSecret } : null);
          setStep('payment');
          return;
        }
        if (Date.now() - startedAt > POLL_TIMEOUT) {
          stopPolling();
          setError('Payment intent not ready after timeout');
          setStep('failed');
          return;
        }
      } catch (err: unknown) {
        const isNotFound = err instanceof Error && err.message.includes('404');
        if (isNotFound) {
          // 404 expected — payment record not yet created by async consumer
          if (Date.now() - startedAt > POLL_TIMEOUT) {
            stopPolling();
            setError('Payment intent not created after timeout');
            setStep('failed');
            return;
          }
          return; // continue polling
        }
        consecutiveErrors++;
        if (consecutiveErrors >= MAX_RETRIES) {
          stopPolling();
          setError('Failed to fetch payment intent');
          setStep('failed');
        }
      }
    }, POLL_INTERVAL);
  }, [stopPolling]);

  const pollOrderStatus = useCallback((orderId: number, startedAt: number) => {
    if (pollRef.current) return;
    pollRef.current = setInterval(async () => {
      try {
        const updated = await api.getOrder(orderId);
        if (updated.status !== 'PENDING') {
          stopPolling();
          setOrder(updated);
          if (updated.status === 'PAID') {
            setStep('done');
          } else {
            setStep('failed');
          }
        } else if (Date.now() - startedAt > POLL_TIMEOUT) {
          stopPolling();
          setStep('done');
        }
      } catch {
        stopPolling();
      }
    }, POLL_INTERVAL);
  }, [stopPolling]);

  useEffect(() => {
    return () => stopPolling();
  }, [stopPolling]);

  if (!isAuthenticated) {
    navigate('/login', { replace: true });
    return null;
  }

  if (items.length === 0 && step === 'review') {
    return (
      <div className="page">
        <h1>Checkout</h1>
        <p>Your cart is empty.</p>
        <Link to="/catalog" className="btn">Browse Products</Link>
      </div>
    );
  }

  if (step === 'done' && order) {
    return (
      <div className="page">
        <h1>Order Placed!</h1>
        <p>Total: <strong>${Number(order.total).toFixed(2)}</strong></p>
        <p>Status: <span className={`status status-${order.status.toLowerCase()}`}>{order.status}</span></p>
        <p style={{ marginTop: 8, color: 'var(--text-secondary)' }}>
          Payment confirmed. Your order is being processed.
        </p>
        <Link to={`/orders/${order.id}`} className="btn" style={{ marginTop: 16 }}>View Order</Link>
      </div>
    );
  }

  if (step === 'failed' && order) {
    return (
      <div className="page">
        <h1>Payment Failed</h1>
        <p>Order #{order.id} — Status: <span className={`status status-${order.status.toLowerCase()}`}>{order.status}</span></p>
        <p style={{ marginTop: 8, color: 'var(--danger)' }}>
          The payment could not be completed. Please try again or contact support.
        </p>
        <Link to="/cart" className="btn" style={{ marginTop: 16 }}>Back to Cart</Link>
      </div>
    );
  }

  const handlePlaceOrder = async () => {
    setStep('placing');
    setError('');
    try {
      const result = await api.placeOrder({
        items: items.map(i => ({ productId: i.product.id, quantity: i.quantity })),
      });
      setOrder(result);
      clearCart();
      if (result.clientSecret) {
        setStep('payment');
      } else {
        // Poll for clientSecret from payment service
        setStep('waiting_payment');
        pollForClientSecret(result.id, Date.now());
      }
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to place order');
      setStep('review');
    }
  };

  const handlePaymentError = (msg: string) => {
    setError(msg);
    setStep('payment');
  };

  const handlePaymentConfirmed = useCallback(async () => {
    if (!order) return;
    setStep('confirming');
    pollOrderStatus(order.id, Date.now());
    try {
      await api.syncPaymentIntent(order.id);
    } catch (e) {
      console.error('Payment sync failed', e);
    }
  }, [order, pollOrderStatus]);

  return (
    <div className="page checkout-page">
      <h1>Checkout</h1>

      {step === 'confirming' && order && (
        <div style={{ textAlign: 'center', padding: '2rem 0' }}>
          <div className="spinner" style={{ margin: '0 auto 1rem' }} />
          <p>Payment processing…</p>
          <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginTop: 8 }}>
            Order #{order.id} — confirming with Stripe
          </p>
        </div>
      )}

      {step === 'waiting_payment' && order && (
        <div style={{ textAlign: 'center', padding: '2rem 0' }}>
          <div className="spinner" style={{ margin: '0 auto 1rem' }} />
          <p>Preparing payment…</p>
          <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginTop: 8 }}>
            Order #{order.id} — waiting for payment intent
          </p>
        </div>
      )}

      {(step === 'review' || step === 'placing' || step === 'payment') && (
        <>
          {step === 'review' || step === 'placing' ? (
            <>
              <div className="checkout-items">
                {items.map(({ product, quantity }) => (
                  <div key={product.id} className="checkout-item">
                    <span>{product.name} × {quantity}</span>
                    <span>${(product.price * quantity).toFixed(2)}</span>
                  </div>
                ))}
              </div>
              <h2>Total: ${total.toFixed(2)}</h2>
              {error && <p className="error">{error}</p>}
              <button
                className="btn btn-primary"
                disabled={step === 'placing'}
                onClick={handlePlaceOrder}
              >
                {step === 'placing' ? 'Placing Order...' : 'Place Order'}
              </button>
            </>
          ) : step === 'payment' && order?.clientSecret ? (
            <>
              <p style={{ marginBottom: 16 }}>
                Order #{order.id} — Total: <strong>${Number(order.total).toFixed(2)}</strong>
              </p>
              {error && <p className="error">{error}</p>}
              <Elements
                stripe={stripePromise}
                options={{ clientSecret: order.clientSecret }}
              >
                <PaymentForm
                  orderId={order.id}
                  onPaymentConfirmed={handlePaymentConfirmed}
                  onError={handlePaymentError}
                />
              </Elements>
            </>
          ) : step === 'payment' ? (
            <p>Loading payment form…</p>
          ) : null}
        </>
      )}
    </div>
  );
}
