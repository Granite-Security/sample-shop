import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router';
import { api } from '../api';
import { useAuth } from '../auth';
import { formatPrice, useShop } from '../store';
import type { AddressResponse, DeliveryAddress, OrderResponse, ProviderPayload } from '../types';
import { ChocolateArt, variantFor } from '../components/ChocolateArt';
import PaymentWidget from '../components/payment/PaymentWidget';
import ProviderSelector from '../components/payment/ProviderSelector';
import { usePaymentProviders } from '../components/payment/usePaymentProviders';

// Flow ported from ui-shop/src/pages/Checkout.tsx:
// place order → poll for the provider payload → PaymentWidget (switched on the
// provider's confirmation mode) → confirm → sync the intent → poll until the
// order leaves PENDING.
type Step = 'review' | 'placing' | 'waiting_payment' | 'payment' | 'confirming' | 'done' | 'failed';

const POLL_INTERVAL = 1000;
const POLL_TIMEOUT = 30000;
const MAX_RETRIES = 25;

const EMPTY_ADDRESS: DeliveryAddress = {
  recipientName: '',
  addressLine1: '',
  addressLine2: '',
  city: '',
  state: '',
  zipCode: '',
  country: '',
};

const inputStyle =
  'w-full border border-cocoa/20 bg-white/70 px-4 py-3 text-sm text-cocoa placeholder:text-cocoa/40 focus:border-gold focus:outline-none';

export function CheckoutPage() {
  const { cart, cartTotal, clearCart } = useShop();
  const { isAuthenticated, loading: authLoading, login } = useAuth();
  const [step, setStep] = useState<Step>('review');
  const [error, setError] = useState('');
  const [order, setOrder] = useState<OrderResponse | null>(null);
  const [addresses, setAddresses] = useState<AddressResponse[]>([]);
  const [address, setAddress] = useState<DeliveryAddress>(EMPTY_ADDRESS);
  const [useSaved, setUseSaved] = useState<number | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const { providers, selected: selectedProvider, setSelected: setSelectedProvider, find } =
    usePaymentProviders();

  // Which provider actually took the order, once payment has opened one — that is
  // authoritative over the shopper's selection, which is only a request.
  const activeProvider = order?.provider ?? selectedProvider;
  const activeMode = find(activeProvider)?.confirmationMode ?? 'CLIENT_SDK';

  const paymentPayload: ProviderPayload | undefined = useMemo(
    () => order?.providerPayload ?? undefined,
    [order],
  );

  const stopPolling = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  useEffect(() => () => stopPolling(), [stopPolling]);

  useEffect(() => {
    window.scrollTo(0, 0);
    if (!isAuthenticated) return;
    api
      .getAddresses()
      .then((addrs) => {
        setAddresses(addrs);
        const def = addrs.find((a) => a.isDefault) ?? addrs[0];
        if (def) {
          setUseSaved(def.id);
          setAddress({
            recipientName: def.recipientName,
            addressLine1: def.addressLine1,
            addressLine2: def.addressLine2 ?? '',
            city: def.city,
            state: def.state ?? '',
            zipCode: def.zipCode,
            country: def.country,
          });
        }
      })
      .catch(() => {});
  }, [isAuthenticated]);

  const pollForClientSecret = useCallback(
    (orderId: number, startedAt: number) => {
      if (pollRef.current) return;
      let consecutiveErrors = 0;
      pollRef.current = setInterval(async () => {
        try {
          const payment = await api.getPaymentIntent(orderId);
          const payload = payment.providerPayload;
          if (payload && Object.keys(payload).length > 0) {
            stopPolling();
            setOrder((prev) =>
              prev ? { ...prev, provider: payment.provider, providerPayload: payload } : null,
            );
            setStep('payment');
            return;
          }
          if (Date.now() - startedAt > POLL_TIMEOUT) {
            stopPolling();
            setError('The payment could not be prepared in time. Please try again.');
            setStep('failed');
          }
        } catch (err) {
          const isNotFound = err instanceof Error && err.message.includes('404');
          if (isNotFound) {
            if (Date.now() - startedAt > POLL_TIMEOUT) {
              stopPolling();
              setError('The payment could not be prepared in time. Please try again.');
              setStep('failed');
            }
            return;
          }
          consecutiveErrors++;
          if (consecutiveErrors >= MAX_RETRIES) {
            stopPolling();
            setError('Failed to reach the payment service.');
            setStep('failed');
          }
        }
      }, POLL_INTERVAL);
    },
    [stopPolling],
  );

  const pollOrderStatus = useCallback(
    (orderId: number) => {
      if (pollRef.current) return;
      pollRef.current = setInterval(async () => {
        try {
          const updated = await api.getOrder(orderId);
          if (updated?.status && updated.status !== 'PENDING') {
            stopPolling();
            setOrder(updated);
            setStep(updated.status === 'PAID' ? 'done' : 'failed');
          }
        } catch {
          stopPolling();
        }
      }, POLL_INTERVAL);
    },
    [stopPolling],
  );

  const placeOrder = async () => {
    if (!address.recipientName || !address.addressLine1 || !address.city || !address.zipCode || !address.country) {
      setError('Please complete the delivery address.');
      return;
    }
    setStep('placing');
    setError('');
    try {
      // Fallback pieces (negative ids) exist only client-side and can't be ordered.
      const result = await api.placeOrder({
        items: cart
          .filter((l) => l.product.id > 0)
          .map((l) => ({ productId: l.product.id, quantity: l.quantity })),
        address,
        // Null while one provider is enabled; payment fills it in. Once several
        // are, the selector has already forced a choice.
        provider: selectedProvider ?? undefined,
      });
      setOrder(result);
      clearCart();
      // shop never fills this in — the SPA fetches it from payment — so this is
      // effectively always the polling branch. Kept in case that ever changes.
      if (result.providerPayload) {
        setStep('payment');
      } else {
        setStep('waiting_payment');
        pollForClientSecret(result.id, Date.now());
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to place the order.');
      setStep('review');
    }
  };

  const onPaymentConfirmed = useCallback(async () => {
    if (!order) return;
    setStep('confirming');
    try {
      await api.syncPaymentIntent(order.id);
    } catch {
      // the webhook usually settles the order anyway
    }
    pollOrderStatus(order.id);
  }, [order, pollOrderStatus]);

  const liveItems = cart.filter((l) => l.product.id > 0);
  const fallbackItems = cart.filter((l) => l.product.id < 0);

  return (
    <div className="bg-ivory pt-28 lg:pt-32">
      <div className="mx-auto max-w-3xl px-5 pb-24 lg:px-8">
        <p className="text-xs uppercase tracking-[0.3em] text-terracotta">Checkout</p>
        <h1 className="mt-3 font-display text-[36px] leading-tight text-cocoa lg:text-[48px]">
          {step === 'done' ? 'Thank You' : 'Almost Yours'}
        </h1>

        {authLoading ? (
          <p className="mt-8 text-cocoa/50">Loading…</p>
        ) : !isAuthenticated ? (
          <div className="mt-8">
            <p className="max-w-md text-cocoa/70">
              Sign in to complete your order — your cart will be waiting for you.
            </p>
            <button
              onClick={login}
              className="mt-6 bg-cocoa px-10 py-4 text-xs uppercase tracking-[0.2em] text-ivory transition-colors duration-300 hover:bg-espresso"
            >
              Sign In to Continue
            </button>
          </div>
        ) : step === 'done' && order ? (
          <div className="mt-8">
            <p className="max-w-md text-cocoa/70">
              Your payment was received and order <span className="font-display text-cocoa">#{order.id}</span> is
              being prepared in the atelier.
            </p>
            <p className="mt-2 text-sm text-sage">
              Status: {order.status} · {formatPrice(order.total)}
            </p>
            <Link
              to="/"
              className="mt-8 inline-block border border-cocoa px-8 py-3 text-xs uppercase tracking-[0.18em] text-cocoa transition-colors duration-300 hover:bg-cocoa hover:text-ivory"
            >
              Back to the Boutique
            </Link>
          </div>
        ) : step === 'failed' ? (
          <div className="mt-8">
            <p className="border-l-2 border-terracotta bg-terracotta/10 px-4 py-3 text-sm text-terracotta">
              {error || 'The payment could not be completed.'}
              {order && ` (order #${order.id} — ${order.status})`}
            </p>
            <button
              onClick={() => {
                setStep('review');
                setError('');
                setOrder(null);
              }}
              className="mt-6 border border-cocoa px-8 py-3 text-xs uppercase tracking-[0.18em] text-cocoa transition-colors duration-300 hover:bg-cocoa hover:text-ivory"
            >
              Try Again
            </button>
          </div>
        ) : step === 'waiting_payment' || step === 'confirming' ? (
          <div className="mt-12 flex flex-col items-center gap-4 text-center">
            <div className="h-10 w-10 animate-spin rounded-full border-2 border-cocoa/20 border-t-gold" />
            <p className="text-cocoa/70">
              {step === 'waiting_payment' ? 'Preparing your payment…' : 'Payment confirmed — finalizing your order…'}
            </p>
            {order && <p className="text-sm text-cocoa/50">Order #{order.id}</p>}
          </div>
        ) : step === 'payment' && order && paymentPayload && activeProvider ? (
          <div className="mt-8">
            <p className="mb-6 text-sm text-cocoa/70">
              Order <span className="font-display text-cocoa">#{order.id}</span> ·{' '}
              <span className="text-terracotta">{formatPrice(order.total)}</span>
            </p>
            {error && (
              <p className="mb-4 border-l-2 border-terracotta bg-terracotta/10 px-4 py-3 text-sm text-terracotta">
                {error}
              </p>
            )}
            <PaymentWidget
              key={order.id}
              provider={activeProvider}
              displayName={find(activeProvider)?.displayName}
              confirmationMode={activeMode}
              payload={paymentPayload}
              orderId={order.id}
              onPaymentConfirmed={onPaymentConfirmed}
              onError={(msg) => {
                setError(msg);
                setStep('payment');
              }}
            />
          </div>
        ) : cart.length === 0 ? (
          <div className="mt-8">
            <p className="text-cocoa/70">Your cart is empty.</p>
            <Link
              to="/shop"
              className="mt-6 inline-block border border-cocoa px-8 py-3 text-xs uppercase tracking-[0.18em] text-cocoa transition-colors duration-300 hover:bg-cocoa hover:text-ivory"
            >
              Browse the Collection
            </Link>
          </div>
        ) : (
          <div className="mt-10 space-y-10">
            {/* order summary */}
            <section aria-label="Order summary">
              <h2 className="font-display text-[22px] text-cocoa">Your Selection</h2>
              <ul className="mt-4 divide-y divide-cocoa/10 border-y border-cocoa/10">
                {cart.map(({ product, quantity }) => (
                  <li key={product.id} className="flex items-center gap-4 py-4">
                    <div className="h-14 w-14 shrink-0 overflow-hidden rounded-md">
                      <ChocolateArt
                        seed={product.id}
                        variant={variantFor(product.name, product.id)}
                        className="h-full w-full"
                      />
                    </div>
                    <span className="flex-1 text-cocoa">
                      {product.name} <span className="text-cocoa/50">× {quantity}</span>
                    </span>
                    <span className="text-cocoa">{formatPrice(product.price * quantity)}</span>
                  </li>
                ))}
              </ul>
              <p className="mt-4 flex justify-between font-display text-xl text-cocoa">
                <span>Total</span>
                <span>{formatPrice(cartTotal)}</span>
              </p>
              {fallbackItems.length > 0 && (
                <p className="mt-3 text-sm text-terracotta">
                  {liveItems.length === 0
                    ? "These pieces are from the editorial preview catalog and can't be ordered — the shop backend isn't reachable."
                    : 'Preview-catalog pieces in your cart will be left out of the order.'}
                </p>
              )}
            </section>

            {/* delivery address */}
            <section aria-label="Delivery address">
              <h2 className="font-display text-[22px] text-cocoa">Delivery Address</h2>
              {addresses.length > 0 && (
                <div className="mt-4 space-y-2">
                  {addresses.map((addr) => (
                    <label
                      key={addr.id}
                      className={`flex cursor-pointer items-start gap-3 border px-4 py-3 transition-colors ${
                        useSaved === addr.id ? 'border-gold bg-gold/5' : 'border-cocoa/15'
                      }`}
                    >
                      <input
                        type="radio"
                        name="saved-address"
                        checked={useSaved === addr.id}
                        onChange={() => {
                          setUseSaved(addr.id);
                          setAddress({
                            recipientName: addr.recipientName,
                            addressLine1: addr.addressLine1,
                            addressLine2: addr.addressLine2 ?? '',
                            city: addr.city,
                            state: addr.state ?? '',
                            zipCode: addr.zipCode,
                            country: addr.country,
                          });
                        }}
                        className="mt-1 accent-[#C7A56B]"
                      />
                      <span className="text-sm text-cocoa">
                        <span className="font-medium">{addr.recipientName}</span>
                        {addr.label && <span className="text-cocoa/50"> ({addr.label})</span>}
                        <br />
                        {addr.addressLine1}, {addr.city} {addr.zipCode}, {addr.country}
                      </span>
                    </label>
                  ))}
                  <button
                    onClick={() => {
                      setUseSaved(null);
                      setAddress(EMPTY_ADDRESS);
                    }}
                    className="text-xs uppercase tracking-[0.14em] text-cocoa underline decoration-gold underline-offset-4 hover:text-terracotta"
                  >
                    Use a different address
                  </button>
                </div>
              )}
              {useSaved === null && (
                <div className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2">
                  <input
                    aria-label="Recipient name"
                    placeholder="Recipient name *"
                    value={address.recipientName}
                    onChange={(e) => setAddress({ ...address, recipientName: e.target.value })}
                    className={`${inputStyle} sm:col-span-2`}
                  />
                  <input
                    aria-label="Address line 1"
                    placeholder="Address line 1 *"
                    value={address.addressLine1}
                    onChange={(e) => setAddress({ ...address, addressLine1: e.target.value })}
                    className={`${inputStyle} sm:col-span-2`}
                  />
                  <input
                    aria-label="Address line 2"
                    placeholder="Address line 2"
                    value={address.addressLine2 ?? ''}
                    onChange={(e) => setAddress({ ...address, addressLine2: e.target.value })}
                    className={`${inputStyle} sm:col-span-2`}
                  />
                  <input
                    aria-label="City"
                    placeholder="City *"
                    value={address.city}
                    onChange={(e) => setAddress({ ...address, city: e.target.value })}
                    className={inputStyle}
                  />
                  <input
                    aria-label="State"
                    placeholder="State"
                    value={address.state ?? ''}
                    onChange={(e) => setAddress({ ...address, state: e.target.value })}
                    className={inputStyle}
                  />
                  <input
                    aria-label="ZIP code"
                    placeholder="ZIP code *"
                    value={address.zipCode}
                    onChange={(e) => setAddress({ ...address, zipCode: e.target.value })}
                    className={inputStyle}
                  />
                  <input
                    aria-label="Country"
                    placeholder="Country *"
                    value={address.country}
                    onChange={(e) => setAddress({ ...address, country: e.target.value })}
                    className={inputStyle}
                  />
                </div>
              )}
            </section>

            <ProviderSelector
              providers={providers}
              selected={selectedProvider}
              onSelect={setSelectedProvider}
              disabled={step === 'placing'}
            />

            {error && (
              <p className="border-l-2 border-terracotta bg-terracotta/10 px-4 py-3 text-sm text-terracotta">
                {error}
              </p>
            )}

            <button
              disabled={step === 'placing' || liveItems.length === 0}
              onClick={placeOrder}
              className="w-full bg-cocoa py-4 text-xs uppercase tracking-[0.2em] text-ivory transition-colors duration-300 hover:bg-espresso disabled:cursor-not-allowed disabled:opacity-40"
            >
              {step === 'placing' ? 'Placing Order…' : `Place Order — ${formatPrice(cartTotal)}`}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
