import { request } from './client';
import type { PackagingQuote } from '../types';

export const packagingApi = {
  /**
   * Prices every box option for a cart. Read-only — nothing is stored, so it is
   * safe to re-ask whenever the cart changes.
   *
   * A POST because the cart is the question and a cart does not fit in a query
   * string.
   */
  quote: (items: { productId: number; quantity: number }[]) =>
    request<PackagingQuote>('/api/shop/packaging/quote', {
      method: 'POST',
      body: JSON.stringify({ items }),
    }),
};
