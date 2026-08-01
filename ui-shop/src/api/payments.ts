import { request } from './client';
import type { CreatePaymentIntentResponse, PaymentProviderInfo } from '../types';

export const paymentsApi = {
  // Public: the checkout page needs it before the shopper authenticates.
  listProviders: () =>
    request<PaymentProviderInfo[]>('/api/payments/providers', { skipAuth: true }),

  getPaymentIntent: (orderId: number) =>
    request<CreatePaymentIntentResponse>(`/api/payments/intent/${orderId}`, { skipAuth: true }),

  syncPaymentIntent: (orderId: number) =>
    request<CreatePaymentIntentResponse>(`/api/payments/intent/${orderId}/sync`, {
      method: 'POST',
      skipAuth: true,
    }),

  retryPaymentIntent: (orderId: number) =>
    request<CreatePaymentIntentResponse>(`/api/payments/intent/${orderId}/retry`, {
      method: 'POST',
      skipAuth: true,
    }),
};
