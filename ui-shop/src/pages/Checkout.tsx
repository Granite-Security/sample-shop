import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { useNavigate, Link } from 'react-router';
import { useCart } from '../contexts/CartContext';
import { useAuth } from '../auth';
import { api } from '../api';
import type { OrderResponse, AddressResponse, DeliveryAddress, ProviderPayload } from '../types';
import ErrorBoundary from '../components/ErrorBoundary';
import PaymentWidget from '../components/payment/PaymentWidget';
import ProviderSelector from '../components/payment/ProviderSelector';
import { usePaymentProviders } from '../components/payment/usePaymentProviders';

type Step = 'review' | 'placing' | 'waiting_payment' | 'payment' | 'confirming' | 'done' | 'failed' | 'error';

const POLL_INTERVAL = 1000;
const POLL_TIMEOUT = 30000;
const MAX_RETRIES = 25;

function CheckoutInner() {
  const { items, total, clearCart } = useCart();
  const { isAuthenticated } = useAuth();
  const navigate = useNavigate();
  const [step, setStep] = useState<Step>('review');
  const [error, setError] = useState('');
  const [order, setOrder] = useState<OrderResponse | null>(null);
  const [paymentStatus, setPaymentStatus] = useState<string | null>(null);
  const [addresses, setAddresses] = useState<AddressResponse[]>([]);
  const [selectedAddress, setSelectedAddress] = useState<DeliveryAddress | null>(null);
  const [showAddressForm, setShowAddressForm] = useState(false);
  const [newAddress, setNewAddress] = useState<DeliveryAddress>({
    recipientName: '', addressLine1: '', addressLine2: '', city: '', state: '', zipCode: '', country: '',
  });
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const { providers, selected: selectedProvider, setSelected: setSelectedProvider, find } =
    usePaymentProviders();

  // Which provider actually took the order, once payment has opened one — that is
  // authoritative over the shopper's selection, which is only a request.
  const activeProvider = order?.provider ?? selectedProvider;
  const activeMode = find(activeProvider)?.confirmationMode ?? 'CLIENT_SDK';

  const paymentPayload: ProviderPayload | undefined = useMemo(() => {
    if (order?.providerPayload) return order.providerPayload;
    // Legacy flat field, still populated by the API as a deprecated alias.
    return order?.clientSecret ? { clientSecret: order.clientSecret } : undefined;
    // Depends on `order` as a whole: the React Compiler infers that from reading two
    // of its properties, and a narrower list here disables optimisation entirely.
  }, [order]);

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
        const payment = await api.payments.getPaymentIntent(orderId);
        const payload = payment.providerPayload
          ?? (payment.clientSecret ? { clientSecret: payment.clientSecret } : null);
        if (payload && Object.keys(payload).length > 0) {
          stopPolling();
          setOrder(prev => prev
            ? { ...prev, provider: payment.provider, providerPayload: payload }
            : null);
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
          if (Date.now() - startedAt > POLL_TIMEOUT) {
            stopPolling();
            setError('Payment intent not created after timeout');
            setStep('failed');
            return;
          }
          return;
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

  const pollOrderStatus = useCallback((orderId: number) => {
    if (pollRef.current) return;
    pollRef.current = setInterval(async () => {
      try {
        const [updated, payment] = await Promise.all([
          api.orders.getOrder(orderId),
          api.payments.getPaymentIntent(orderId),
        ]);
        if (payment) setPaymentStatus(payment.status);
        if (updated && updated.status && updated.status !== 'PENDING') {
          stopPolling();
          setOrder(updated);
          if (updated.status === 'PAID') {
            setStep('done');
          } else {
            setStep('failed');
          }
        }
      } catch (err) {
        console.error('pollOrderStatus error', err);
        stopPolling();
      }
    }, POLL_INTERVAL);
  }, [stopPolling]);

  useEffect(() => {
    return () => stopPolling();
  }, [stopPolling]);

  useEffect(() => {
    if (!isAuthenticated) return;
    api.profile.getAddresses()
      .then(addrs => {
        setAddresses(addrs);
        const def = addrs.find(a => a.isDefault) ?? addrs[0];
        if (def) setSelectedAddress({
          recipientName: def.recipientName,
          addressLine1: def.addressLine1,
          addressLine2: def.addressLine2 ?? undefined,
          city: def.city,
          state: def.state ?? undefined,
          zipCode: def.zipCode,
          country: def.country,
        });
      })
      .catch(() => {});
  }, [isAuthenticated]);

  // With one provider the hook pre-selects it and the selector stays hidden, so this
  // is never unsatisfied. With several, the shopper has to say who takes their money —
  // shop rejects an order that names no provider, and a raw 400 is a poor way to
  // discover that a radio button was missed.
  const needsProviderChoice = providers.length > 1 && !selectedProvider;

  const handlePlaceOrder = async () => {
    if (!selectedAddress) {
      setError('Please select a delivery address');
      return;
    }
    if (needsProviderChoice) {
      setError('Please choose a payment method');
      return;
    }
    setStep('placing');
    setError('');
    try {
      const result = await api.orders.placeOrder({
        items: items.map(i => ({ productId: i.product.id, quantity: i.quantity })),
        address: selectedAddress,
        // Null while one provider is enabled; payment fills it in. Once several are,
        // the selector has already forced a choice.
        provider: selectedProvider ?? undefined,
      });
      setOrder(result);
      clearCart();
      // shop never fills these in — the SPA fetches them from payment — so this is
      // effectively always the polling branch. Kept in case that ever changes.
      if (result.providerPayload || result.clientSecret) {
        setStep('payment');
      } else {
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
    try {
      await api.payments.syncPaymentIntent(order.id);
    } catch (e) {
      console.error('Payment sync failed', e);
    }
    pollOrderStatus(order.id);
  }, [order, pollOrderStatus]);

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

  if (step === 'done') {
    return <ThankYou order={order} />;
  }

  if (step === 'failed') {
    const statusLabel = order?.status?.toLowerCase() ?? '';
    if (!order) {
      return (
        <div className="page">
          <h1>Payment Failed</h1>
          <p>The payment could not be completed. Please try again or contact support.</p>
          <Link to="/cart" className="btn" style={{ marginTop: 16 }}>Back to Cart</Link>
        </div>
      );
    }
    return (
      <div className="page">
        <h1>Payment Failed</h1>
        <p>Order #{order.id} — Status: {statusLabel ? <span className={`status status-${statusLabel}`}>{order.status}</span> : 'Unknown'}</p>
        <p style={{ marginTop: 8, color: 'var(--danger)' }}>
          The payment could not be completed. Please try again or contact support.
        </p>
        <Link to="/cart" className="btn" style={{ marginTop: 16 }}>Back to Cart</Link>
      </div>
    );
  }

  return (
    <div className="page checkout-page">
      <h1>Checkout</h1>

      {step === 'confirming' && order && (
        <div style={{ textAlign: 'center', padding: '2rem 0' }}>
          <div className="spinner" style={{ margin: '0 auto 1rem' }} />
          <p>Payment confirmed — finalizing your order…</p>
          {paymentStatus && (
            <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginTop: 8 }}>
              Payment: <span className={`status status-${paymentStatus.toLowerCase()}`}>{paymentStatus}</span>
              &nbsp;— Order: <span className={`status status-${order.status.toLowerCase()}`}>{order.status}</span>
            </p>
          )}
        </div>
      )}

      {step === 'confirming' && !order && (
        <div style={{ textAlign: 'center', padding: '2rem 0' }}>
          <div className="spinner" style={{ margin: '0 auto 1rem' }} />
          <p>Payment confirmed — finalizing your order…</p>
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
              <h2>Delivery Address</h2>
              {addresses.length > 0 && (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginBottom: 12 }}>
                  {addresses.map(addr => (
                    <label key={addr.id} style={{
                      display: 'flex', alignItems: 'flex-start', gap: 8, padding: 8,
                      border: `1px solid ${selectedAddress?.addressLine1 === addr.addressLine1 && selectedAddress?.zipCode === addr.zipCode ? 'var(--primary)' : 'var(--border)'}`,
                      borderRadius: 6, cursor: 'pointer', background: selectedAddress?.addressLine1 === addr.addressLine1 && selectedAddress?.zipCode === addr.zipCode ? 'var(--surface-hover)' : 'transparent',
                    }}>
                      <input
                        type="radio"
                        name="address"
                        checked={selectedAddress?.addressLine1 === addr.addressLine1 && selectedAddress?.zipCode === addr.zipCode}
                        onChange={() => setSelectedAddress({
                          recipientName: addr.recipientName,
                          addressLine1: addr.addressLine1,
                          addressLine2: addr.addressLine2 ?? undefined,
                          city: addr.city,
                          state: addr.state ?? undefined,
                          zipCode: addr.zipCode,
                          country: addr.country,
                        })}
                        style={{ marginTop: 3 }}
                      />
                      <div>
                        <strong>{addr.recipientName}</strong>
                        {addr.label && <span style={{ fontSize: '0.8rem', color: 'var(--text-secondary)', marginLeft: 6 }}>({addr.label})</span>}
                        <p style={{ margin: '2px 0', fontSize: '0.9rem' }}>{addr.addressLine1}</p>
                        <p style={{ margin: '2px 0', fontSize: '0.9rem', color: 'var(--text-secondary)' }}>
                          {addr.city}{addr.state ? `, ${addr.state}` : ''} {addr.zipCode}, {addr.country}
                        </p>
                      </div>
                    </label>
                  ))}
                </div>
              )}
              {showAddressForm ? (
                <div style={{ border: '1px solid var(--border)', padding: 12, borderRadius: 6, marginBottom: 12 }}>
                  <h3 style={{ margin: '0 0 8px' }}>New Address</h3>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                    <input placeholder="Recipient Name *" value={newAddress.recipientName} onChange={e => setNewAddress({ ...newAddress, recipientName: e.target.value })} />
                    <input placeholder="Address Line 1 *" value={newAddress.addressLine1} onChange={e => setNewAddress({ ...newAddress, addressLine1: e.target.value })} style={{ gridColumn: '1 / -1' }} />
                    <input placeholder="Address Line 2" value={newAddress.addressLine2 ?? ''} onChange={e => setNewAddress({ ...newAddress, addressLine2: e.target.value })} style={{ gridColumn: '1 / -1' }} />
                    <input placeholder="City *" value={newAddress.city} onChange={e => setNewAddress({ ...newAddress, city: e.target.value })} />
                    <input placeholder="State" value={newAddress.state ?? ''} onChange={e => setNewAddress({ ...newAddress, state: e.target.value })} />
                    <input placeholder="ZIP Code *" value={newAddress.zipCode} onChange={e => setNewAddress({ ...newAddress, zipCode: e.target.value })} />
                    <input placeholder="Country *" value={newAddress.country} onChange={e => setNewAddress({ ...newAddress, country: e.target.value })} />
                  </div>
                  <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
                    <button className="btn btn-primary" onClick={() => { setSelectedAddress({ ...newAddress }); setShowAddressForm(false); }}>
                      Use This Address
                    </button>
                    <button className="btn" onClick={() => setShowAddressForm(false)}>Cancel</button>
                  </div>
                </div>
              ) : (
                <button className="btn" style={{ marginBottom: 12 }} onClick={() => setShowAddressForm(true)}>
                  {addresses.length > 0 ? '+ Use a different address' : '+ Add delivery address'}
                </button>
              )}
              {selectedAddress && (
                <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginBottom: 12 }}>
                  Shipping to: <strong>{selectedAddress.recipientName}</strong>, {selectedAddress.addressLine1}, {selectedAddress.city}, {selectedAddress.country}
                </p>
              )}
              <h2>Total: ${total.toFixed(2)}</h2>
              <ProviderSelector
                providers={providers}
                selected={selectedProvider}
                onSelect={setSelectedProvider}
                disabled={step === 'placing'}
              />
              {error && <p className="error">{error}</p>}
              <button
                className="btn btn-primary"
                disabled={step === 'placing' || !selectedAddress || needsProviderChoice}
                onClick={handlePlaceOrder}
              >
                {step === 'placing' ? 'Placing Order...' : 'Place Order'}
              </button>
            </>
          ) : step === 'payment' && order && paymentPayload && activeProvider ? (
            <>
              <p style={{ marginBottom: 16 }}>
                Order #{order.id} — Total: <strong>${Number(order.total).toFixed(2)}</strong>
              </p>
              {error && <p className="error">{error}</p>}
              <PaymentWidget
                key={order.id}
                provider={activeProvider}
                displayName={find(activeProvider)?.displayName}
                confirmationMode={activeMode}
                payload={paymentPayload}
                orderId={order.id}
                onPaymentConfirmed={handlePaymentConfirmed}
                onError={handlePaymentError}
              />
            </>
          ) : step === 'payment' ? (
            <p>Loading payment form…</p>
          ) : null}
        </>
      )}
    </div>
  );
}

export default function Checkout() {
  return (
    <ErrorBoundary>
      <CheckoutInner />
    </ErrorBoundary>
  );
}

function ThankYou({ order }: { order: OrderResponse | null }) {
  const navigate = useNavigate();
  const id = order?.id;
  const statusLabel = order?.status?.toLowerCase() ?? 'paid';

  useEffect(() => {
    if (!id) return;
    const t = setTimeout(() => navigate(`/orders/${id}`, { replace: true }), 4000);
    return () => clearTimeout(t);
  }, [id, navigate]);

  return (
    <div className="page" style={{ textAlign: 'center', paddingTop: '2rem' }}>
      <h1>Thank You for Your Purchase!</h1>
      <p style={{ fontSize: '1.1rem', marginTop: 8 }}>
        Your payment was successful and your order is being processed.
      </p>
      {order && (
        <>
          <p style={{ marginTop: 16 }}>
            Order{' '}
            <Link to={`/orders/${order.id}`} style={{ fontWeight: 700 }}>
              #{order.id}
            </Link>
            {' '}—{' '}
            <span className={`status status-${statusLabel}`}>{order.status}</span>
            {' '}—{' '}
            <strong>${Number(order.total).toFixed(2)}</strong>
          </p>
        </>
      )}
      <p style={{ marginTop: 24, color: 'var(--text-secondary)' }}>
        Redirecting to your order in 4 seconds…
      </p>
      <Link to={id ? `/orders/${id}` : '/orders'} className="btn" style={{ marginTop: 12 }}>
        {id ? 'View Order' : 'View Orders'}
      </Link>
    </div>
  );
}
