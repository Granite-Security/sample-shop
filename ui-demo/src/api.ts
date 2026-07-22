import type {
  PagedResult,
  Product,
  Category,
  CreateProductRequest,
  OrderResponse,
  PlaceOrderRequest,
  CreatePaymentIntentResponse,
  AddressResponse,
  AddressRequest,
  ProfileResponse,
  UpdateProfileRequest,
} from './types';

// Same-origin calls through the gateway, exactly like ui-shop. Browsing the
// catalog is public; once logged in (spa-client via the auth-server) the
// access token is attached as a Bearer header, same as ui-shop/src/api.ts.
const BASE = '';

let accessToken: string | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string>),
  };
  if (accessToken) {
    headers['Authorization'] = `Bearer ${accessToken}`;
  }
  const res = await fetch(`${BASE}${path}`, {
    ...options,
    headers,
    cache: 'no-store',
  });
  if (res.status === 204) return undefined as T;
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    const msg = body?.detail ?? body?.title ?? res.statusText;
    throw new Error(`[${res.status}] ${msg}`);
  }
  return res.json();
}

export const api = {
  greetings: () => request<{ message: string }>('/api/shop/greetings'),

  getProducts: (page = 0, size = 50) =>
    request<PagedResult<Product>>(`/api/shop/products?page=${page}&size=${size}`),

  getProduct: (id: number) => request<Product>(`/api/shop/products/${id}`),

  getCategories: (page = 0, size = 50) =>
    request<PagedResult<Category>>(`/api/shop/categories?page=${page}&size=${size}`),

  // Orders + payment — same flow as ui-shop's checkout.
  placeOrder: (body: PlaceOrderRequest) =>
    request<OrderResponse>('/api/shop/orders', { method: 'POST', body: JSON.stringify(body) }),

  getOrder: (id: number) => request<OrderResponse>(`/api/shop/orders/${id}`),

  getPaymentIntent: (orderId: number) =>
    request<CreatePaymentIntentResponse>(`/api/payments/intent/${orderId}`),

  syncPaymentIntent: (orderId: number) =>
    request<CreatePaymentIntentResponse>(`/api/payments/intent/${orderId}/sync`, { method: 'POST' }),

  getMyProfile: () => request<ProfileResponse>('/api/profiles/me'),

  updateMyProfile: (body: UpdateProfileRequest) =>
    request<ProfileResponse>('/api/profiles/me', { method: 'PUT', body: JSON.stringify(body) }),

  getAddresses: () => request<AddressResponse[]>('/api/profiles/me/addresses'),

  createAddress: (body: AddressRequest) =>
    request<AddressResponse>('/api/profiles/me/addresses', { method: 'POST', body: JSON.stringify(body) }),

  updateAddress: (id: number, body: AddressRequest) =>
    request<AddressResponse>(`/api/profiles/me/addresses/${id}`, { method: 'PUT', body: JSON.stringify(body) }),

  deleteAddress: (id: number) =>
    request<void>(`/api/profiles/me/addresses/${id}`, { method: 'DELETE' }),

  // Admin-only endpoints — the shop service requires ROLE_ADMIN on the JWT.
  getAllOrders: (page = 0, size = 50) =>
    request<PagedResult<OrderResponse>>(`/api/shop/orders/all?page=${page}&size=${size}`),

  createProduct: (body: CreateProductRequest) =>
    request<Product>('/api/shop/products', { method: 'POST', body: JSON.stringify(body) }),

  updateProduct: (id: number, body: CreateProductRequest) =>
    request<Product>(`/api/shop/products/${id}`, { method: 'PUT', body: JSON.stringify(body) }),

  deleteProduct: (id: number) =>
    request<void>(`/api/shop/products/${id}`, { method: 'DELETE' }),
};

// Editorial fallback catalog — shown when the shop backend isn't reachable
// (e.g. running `npm run dev` without the kind cluster up), so the demo
// always renders a complete storefront.
export const FALLBACK_PRODUCTS: Product[] = [
  {
    id: -1,
    name: 'Ecuador 72% Single-Origin Bar',
    description: 'Arriba Nacional cacao with notes of dried fig, jasmine and toasted hazelnut.',
    price: 12.5,
    stock: 120,
    categoryId: 1,
    imageUrl: '',
  },
  {
    id: -2,
    name: 'Sea Salt Caramel Truffles',
    description: 'Slow-simmered caramel enrobed in dark couverture, finished with fleur de sel.',
    price: 24.0,
    stock: 80,
    categoryId: 2,
    imageUrl: '',
  },
  {
    id: -3,
    name: 'Madagascar 85% Intense',
    description: 'Bright red-berry acidity and deep cocoa — our boldest single-origin pour.',
    price: 13.5,
    stock: 90,
    categoryId: 1,
    imageUrl: '',
  },
  {
    id: -4,
    name: 'The Signature Gift Box',
    description: 'Sixteen hand-finished pralines and truffles in our espresso keepsake box.',
    price: 48.0,
    stock: 45,
    categoryId: 3,
    imageUrl: '',
  },
  {
    id: -5,
    name: 'Pistachio & Rose Praline',
    description: 'Sicilian pistachio gianduja layered with a whisper of Damask rose.',
    price: 26.0,
    stock: 60,
    categoryId: 2,
    imageUrl: '',
  },
  {
    id: -6,
    name: 'Ghana 65% Velvet Bar',
    description: 'Round, warm and chocolatey — brown butter, honey and a long cocoa finish.',
    price: 11.5,
    stock: 140,
    categoryId: 1,
    imageUrl: '',
  },
  {
    id: -7,
    name: 'Hot Chocolate Flakes',
    description: 'Shaved 70% couverture for the thickest European-style drinking chocolate.',
    price: 18.0,
    stock: 75,
    categoryId: 4,
    imageUrl: '',
  },
  {
    id: -8,
    name: 'Espresso Ganache Collection',
    description: 'Nine dark ganaches infused with single-estate arabica and a gold-dusted top.',
    price: 32.0,
    stock: 50,
    categoryId: 2,
    imageUrl: '',
  },
];

// The single source of truth for "which live products belong to
// sichocolate.com" — matched by name against shop's Food & Sweets category in
// store.tsx, since that category also holds the shared shop's own generic
// products (see docs/plans/add-chocolates.md).
export const CURATED_PRODUCT_NAMES = new Set(FALLBACK_PRODUCTS.map((p) => p.name));
