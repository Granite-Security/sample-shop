import { request } from './client';
import type { DeliveryResponse } from '../types';

export const deliveryApi = {
  getDelivery: (orderId: number) =>
    request<DeliveryResponse>(`/api/delivery/${orderId}`).catch(() => null),

  getDeliveries: (params = '') =>
    request<DeliveryResponse[]>(`/api/delivery${params}`).catch(() => []),

  updateDeliveryStatus: (orderId: number, status: string, description: string) =>
    request<DeliveryResponse>(`/api/delivery/${orderId}/status`, {
      method: 'PUT',
      body: JSON.stringify({ status, description }),
    }),
};
