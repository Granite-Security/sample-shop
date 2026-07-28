import { request } from './client';
import type { OrderResponse, PagedResult, PlaceOrderRequest } from '../types';

export const ordersApi = {
  getOrders: (page = 0, size = 20) =>
    request<PagedResult<OrderResponse>>(`/api/shop/orders?page=${page}&size=${size}`),

  getOrder: (id: number) =>
    request<OrderResponse>(`/api/shop/orders/${id}`),

  placeOrder: (body: PlaceOrderRequest) =>
    request<OrderResponse>('/api/shop/orders', { method: 'POST', body: JSON.stringify(body) }),

  // Admin only. Rooted at /users/, not /orders/, because "{id}" under /orders
  // is an order id and a username segment there would shadow it.
  getOrdersByUsername: (username: string, page = 0, size = 20) =>
    request<PagedResult<OrderResponse>>(
      `/api/shop/users/${encodeURIComponent(username)}/orders?page=${page}&size=${size}`),

  refundOrder: (orderId: number) =>
    request<OrderResponse>(`/api/shop/orders/${orderId}/refund`, { method: 'POST' }),
};
