import { request } from './client';
import type { CreatePaymentIntentResponse } from '../types';

export const paymentsApi = {
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
