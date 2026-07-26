import { request } from './client';
import type { OrderResponse, PagedResult, PlaceOrderRequest } from '../types';

export const ordersApi = {
  getOrders: (page = 0, size = 20) =>
    request<PagedResult<OrderResponse>>(`/api/shop/orders?page=${page}&size=${size}`),

  getOrder: (id: number) =>
    request<OrderResponse>(`/api/shop/orders/${id}`),

  placeOrder: (body: PlaceOrderRequest) =>
    request<OrderResponse>('/api/shop/orders', { method: 'POST', body: JSON.stringify(body) }),

  refundOrder: (orderId: number) =>
    request<OrderResponse>(`/api/shop/orders/${orderId}/refund`, { method: 'POST' }),
};
