import { useEffect, useState } from 'react';
import { api } from '../../api';
import type { PaymentProviderInfo } from '../../types';

/**
 * The enabled payment providers, plus the shopper's current choice.
 *
 * <p>Pre-selects when there is exactly one, so the selector stays invisible and
 * `provider` is still sent — the shopper sees no change while Stripe is alone.
 *
 * <p>A failed fetch is not fatal: `providers` stays empty and the caller falls back
 * to whatever the payment itself reports. Checkout must not be blocked by an
 * endpoint that only decides which radio buttons to draw.
 */
export function usePaymentProviders() {
  const [providers, setProviders] = useState<PaymentProviderInfo[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    api.payments.listProviders()
      .then(list => {
        if (cancelled) return;
        setProviders(list);
        setSelected(prev => prev ?? (list.length === 1 ? list[0].id : null));
      })
      .catch(() => {
        if (!cancelled) setProviders([]);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const find = (id: string | undefined | null) =>
    providers.find(p => p.id === id);

  return { providers, selected, setSelected, loading, find };
}
