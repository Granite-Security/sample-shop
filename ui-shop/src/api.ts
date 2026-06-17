const BASE = 'http://localhost:8080';

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    ...(options.headers as Record<string, string>),
  };

  if (options.body) {
    headers['Content-Type'] = 'application/json';
  }

  const res = await fetch(`${BASE}${path}`, {
    ...options,
    headers,
    credentials: 'include',
  });

  if (res.status === 204) return undefined as T;

  if (res.status === 401) {
    window.location.href = `${BASE}/oauth2/authorization/oidc-client`;
    throw new Error('Unauthorized');
  }

  let data: unknown;
  try {
    data = await res.json();
  } catch {
    const text = await res.text().catch(() => '');
    throw new Error(`Expected JSON, got ${res.status}: ${text.slice(0, 200)}`);
  }
  if (!res.ok) {
    const msg = (data as Record<string, unknown>).detail as string
      ?? (data as Record<string, unknown>).title as string
      ?? res.statusText;
    throw new Error(msg);
  }
  return data as T;
}

import type {
  Product, Category, OrderResponse, PagedResult,
  CreateProductRequest, CreateCategoryRequest, PlaceOrderRequest,
} from './types';

export const api = {
  greetings: () =>
    request<{ message: string }>('/api/shop/greetings'),

  getCategories: (page = 0, size = 50) =>
    request<PagedResult<Category>>(`/api/shop/categories?page=${page}&size=${size}`),

  createCategory: (body: CreateCategoryRequest) =>
    request<Category>('/api/shop/categories', { method: 'POST', body: JSON.stringify(body) }),

  updateCategory: (id: number, body: CreateCategoryRequest) =>
    request<Category>(`/api/shop/categories/${id}`, { method: 'PUT', body: JSON.stringify(body) }),

  deleteCategory: (id: number) =>
    request<void>(`/api/shop/categories/${id}`, { method: 'DELETE' }),

  getProducts: (page = 0, size = 50) =>
    request<PagedResult<Product>>(`/api/shop/products?page=${page}&size=${size}`),

  getProduct: (id: number) =>
    request<Product>(`/api/shop/products/${id}`),

  createProduct: (body: CreateProductRequest) =>
    request<Product>('/api/shop/products', { method: 'POST', body: JSON.stringify(body) }),

  updateProduct: (id: number, body: CreateProductRequest) =>
    request<Product>(`/api/shop/products/${id}`, { method: 'PUT', body: JSON.stringify(body) }),

  deleteProduct: (id: number) =>
    request<void>(`/api/shop/products/${id}`, { method: 'DELETE' }),

  getOrders: (page = 0, size = 20) =>
    request<PagedResult<OrderResponse>>(`/api/shop/orders?page=${page}&size=${size}`),

  getOrder: (id: number) =>
    request<OrderResponse>(`/api/shop/orders/${id}`),

  placeOrder: (body: PlaceOrderRequest) =>
    request<OrderResponse>('/api/shop/orders', { method: 'POST', body: JSON.stringify(body) }),

  me: () =>
    request<{ authenticated: boolean; name?: string; claims?: Record<string, unknown> }>('/api/user/me'),
};
