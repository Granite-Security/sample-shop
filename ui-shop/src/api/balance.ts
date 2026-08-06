import { request } from './client';
import type {
  AccountView,
  BalanceResponse,
  LedgerEntryView,
  ReconcileReport,
  BalanceTransaction,
  CreatePaymentIntentResponse,
  GiftRequest,
  GiftResponse,
  TransferRequest,
  TransferResponse,
} from '../types';

/**
 * The user's CHF balance (docs/finance/finance.md).
 *
 * Served by the balance service under /api/balance/**; top-ups go through
 * payment, because balance never talks to Stripe or PayPal itself.
 */
export const balanceApi = {
  get: () => request<BalanceResponse>('/api/balance/me'),

  transactions: (page = 0, size = 20) =>
    request<BalanceTransaction[]>(`/api/balance/me/transactions?page=${page}&size=${size}`),

  transfer: (body: TransferRequest) =>
    request<TransferResponse>('/api/balance/me/transfers', {
      method: 'POST',
      body: JSON.stringify(body),
    }),

  // Admin only; a 403 here is the server refusing, not the UI.
  gift: (body: GiftRequest) =>
    request<GiftResponse>('/api/balance/admin/gifts', {
      method: 'POST',
      body: JSON.stringify(body),
    }),

  // Treasury, ROLE_ADMIN. `manager` has ROLE_ADMIN too, so one gate covers both.
  reconcile: () => request<ReconcileReport>('/api/balance/admin/reconcile'),

  accounts: () => request<AccountView[]>('/api/balance/admin/accounts'),

  ledger: (page = 0, size = 50) =>
    request<LedgerEntryView[]>(`/api/balance/admin/ledger?page=${page}&size=${size}`),

  // Opens a provider payment that funds the balance. Returns the same shape as
  // an order intent, so the existing payment widgets can complete it.
  createTopupIntent: (amountChf: number, provider: string, currency = 'CHF') =>
    request<CreatePaymentIntentResponse>('/api/payments/topup-intent', {
      method: 'POST',
      body: JSON.stringify({ amount: amountChf, currency, provider }),
    }),

  // The only reliable confirmation for a top-up: provider webhooks resolve
  // payments through an order id, and a top-up has none (finance.md §6.1).
  syncTopup: (paymentId: string) =>
    request<CreatePaymentIntentResponse>(`/api/payments/topup/${paymentId}/sync`, { method: 'POST' }),
};
