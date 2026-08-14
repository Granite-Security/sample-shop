import { request } from './client';
import type { DeliveryQuery, DeliveryResponse, PagedResult, TrackingDetailResponse } from '../types';

/**
 * Filters, sort and paging all go to the server. Filtering a page in the browser
 * filters only the rows that page happens to hold, which looks like data going
 * missing rather than like a filter.
 */
function deliveryQueryString(query: DeliveryQuery): string {
  const params = new URLSearchParams();
  if (query.status) params.set('status', query.status);
  if (query.paymentStatus) params.set('paymentStatus', query.paymentStatus);
  // The picker gives a day; the server takes instants and treats the window as
  // half-open, so `to` is the following midnight.
  if (query.from) params.set('from', new Date(query.from).toISOString());
  if (query.to) params.set('to', new Date(new Date(query.to).getTime() + 86_400_000).toISOString());
  params.set('sort', query.sort ?? 'orderId');
  params.set('dir', query.dir ?? 'desc');
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 20));
  return params.toString();
}

export const deliveryApi = {
  getDelivery: (orderId: number) =>
    request<DeliveryResponse>(`/api/delivery/${orderId}`).catch(() => null),

  getDeliveryTracking: (orderId: number) =>
    request<TrackingDetailResponse>(`/api/delivery/${orderId}/tracking`).catch(() => null),

  getDeliveries: (query: DeliveryQuery = {}) =>
    request<PagedResult<DeliveryResponse>>(`/api/delivery?${deliveryQueryString(query)}`)
      .catch(() => ({ items: [], total: 0, page: 0, size: 20 })),

  updateDeliveryStatus: (orderId: number, status: string, description: string) =>
    request<DeliveryResponse>(`/api/delivery/${orderId}/status`, {
      method: 'PUT',
      body: JSON.stringify({ status, description }),
    }),
};
