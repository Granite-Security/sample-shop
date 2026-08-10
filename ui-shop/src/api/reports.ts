import { request } from './client';
import type { AccrualReport, MoneySupplyReport, RevenueReport } from '../types';

/**
 * The revenue reports (docs/finance/accounting.md §8).
 *
 * One module for three services, because one page owns all three calls. Each
 * number has exactly one owner: shop knows what was sold, balance knows what was
 * conjured, accounting knows what was earned. None of them can answer another's
 * question, and there is no cross-service query anywhere behind these.
 */
const query = (granularity: string, from?: string, to?: string) => {
  const params = new URLSearchParams({ granularity });
  if (from) params.set('from', from);
  if (to) params.set('to', to);
  return params.toString();
};

export const reportsApi = {
  /** Cash: what moved, and when. All orders, whichever provider paid. */
  cash: (granularity: string, currency: string, from?: string, to?: string) =>
    request<RevenueReport>(`/api/shop/admin/revenue?${query(granularity, from, to)}&currency=${currency}`),

  currencies: () =>
    request<{ currencies: string[] }>('/api/shop/admin/revenue/currencies'),

  /** Money creation and the funding split. Balance-paid orders only — never add this to cash. */
  moneySupply: (granularity: string, from?: string, to?: string) =>
    request<MoneySupplyReport>(`/api/balance/admin/money-supply?${query(granularity, from, to)}`),

  /** Accrual: what we earned, recognised on delivery. */
  accrual: (granularity: string, from?: string, to?: string) =>
    request<AccrualReport>(`/api/accounting/revenue?${query(granularity, from, to)}`),
};
