import { request } from './client';
import type { DeliveryResponse, TrackingDetailResponse } from '../types';

export const deliveryApi = {
  getDelivery: (orderId: number) =>
    request<DeliveryResponse>(`/api/delivery/${orderId}`).catch(() => null),

  getDeliveryTracking: (orderId: number) =>
    request<TrackingDetailResponse>(`/api/delivery/${orderId}/tracking`).catch(() => null),

  getDeliveries: (params = '') =>
    request<DeliveryResponse[]>(`/api/delivery${params}`).catch(() => []),

  updateDeliveryStatus: (orderId: number, status: string, description: string) =>
    request<DeliveryResponse>(`/api/delivery/${orderId}/status`, {
      method: 'PUT',
      body: JSON.stringify({ status, description }),
    }),
};
