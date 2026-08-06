import type {
  PagedResult,
  Product,
  Category,
  CreateProductRequest,
  OrderResponse,
  PlaceOrderRequest,
  CreatePaymentIntentResponse,
  PaymentProviderInfo,
  AddressResponse,
  AddressRequest,
  ProfileResponse,
  UpdateProfileRequest,
  MediaItem,
  PresignResponse,
  RegistrationRequest,
  RegistrationResponse,
  UserFile,
  DuplicateFileCheckResponse,
  AdminUserView,
  AdminUserProfile,
  DeleteUserResult,
  AvatarSource,
  BalanceResponse,
  BalanceTransaction,
  TransferRequest,
  TransferResponse,
  GiftResponse,
  MessageResponse,
  RecipientResponse,
  SendMessageRequest,
} from './types';
import { downscaleToSquare } from './utils/avatar';

// Same-origin calls through the gateway, exactly like ui-shop. Browsing the
// catalog is public; once logged in (spa-client via the auth-server) the
// access token is attached as a Bearer header, same as ui-shop/src/api.ts.
const BASE = '';

let accessToken: string | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
}

export class ApiError extends Error {
  status: number;
  data: unknown;

  constructor(status: number, message: string, data: unknown) {
    super(message);
    this.status = status;
    this.data = data;
  }
}

type RequestOptions = RequestInit & { skipAuth?: boolean };

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { skipAuth, ...fetchOptions } = options;
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(fetchOptions.headers as Record<string, string>),
  };
  if (accessToken && !skipAuth) {
    headers['Authorization'] = `Bearer ${accessToken}`;
  }
  const res = await fetch(`${BASE}${path}`, {
    ...fetchOptions,
    headers,
    cache: 'no-store',
  });
  if (res.status === 204) return undefined as T;
  if (res.status === 401) {
    throw new Error('Unauthorized');
  }
  const body = await res.json().catch(() => null);
  if (!res.ok) {
    const msg = body?.detail ?? body?.title ?? res.statusText;
    throw new ApiError(res.status, `[${res.status}] ${msg}`, body);
  }
  return body;
}

export class DuplicateFileError extends Error {
  existingFile: UserFile;

  constructor(existingFile: UserFile) {
    super('This file has already been uploaded.');
    this.existingFile = existingFile;
  }
}

// Hashed locally so a duplicate can be detected — and the upload skipped
// entirely — before any bytes are sent, rather than discovering it only
// after uploading a full copy. Mirrors ui-shop/src/api/profile.ts.
async function sha256Hex(file: File): Promise<string> {
  const buffer = await file.arrayBuffer();
  const digest = await crypto.subtle.digest('SHA-256', buffer);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
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

  getOrders: (page = 0, size = 20) =>
    request<PagedResult<OrderResponse>>(`/api/shop/orders?page=${page}&size=${size}`),

  getOrder: (id: number) => request<OrderResponse>(`/api/shop/orders/${id}`),

  // Public: the checkout page needs it before the shopper authenticates.
  listProviders: () => request<PaymentProviderInfo[]>('/api/payments/providers'),

  getPaymentIntent: (orderId: number) =>
    request<CreatePaymentIntentResponse>(`/api/payments/intent/${orderId}`),

  syncPaymentIntent: (orderId: number) =>
    request<CreatePaymentIntentResponse>(`/api/payments/intent/${orderId}/sync`, { method: 'POST' }),

  getMyProfile: () => request<ProfileResponse>('/api/profiles/me'),

  updateMyProfile: (body: UpdateProfileRequest) =>
    request<ProfileResponse>('/api/profiles/me', { method: 'PUT', body: JSON.stringify(body) }),

  // Avatar. Deliberately separate from updateMyProfile, which overwrites every
  // field it is given (docs/users/user-pic.md D4).
  uploadAvatar: async (file: File): Promise<ProfileResponse> => {
    const square = await downscaleToSquare(file);
    const presigned = await api.presignUpload(square.name, square.type, 'avatars');
    const putResponse = await fetch(presigned.uploadUrl, {
      method: 'PUT',
      headers: { 'Content-Type': square.type },
      body: square,
    });
    if (!putResponse.ok) {
      throw new Error(`Upload failed: ${putResponse.status} ${putResponse.statusText}`);
    }
    return request<ProfileResponse>('/api/profiles/me/avatar', {
      method: 'PUT',
      body: JSON.stringify({
        key: presigned.key,
        url: presigned.publicUrl,
        contentType: square.type,
        sizeBytes: square.size,
      }),
    });
  },

  setAvatarSource: (source: AvatarSource) =>
    request<ProfileResponse>('/api/profiles/me/avatar/source',
      { method: 'PUT', body: JSON.stringify({ source }) }),

  removeAvatar: () =>
    request<ProfileResponse>('/api/profiles/me/avatar', { method: 'DELETE' }),

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

  // Admin user management — mirrors ui-shop/src/api/profile.ts. Note this is
  // the users list, NOT the profiles list: users are a different set from
  // profiles (docs/users/blocking-users.md §2.1, D3).
  getAdminUsers: () => request<AdminUserView[]>('/api/profiles/admin/users'),

  blockUser: (username: string) =>
    request<AdminUserView>(`/api/profiles/admin/users/${encodeURIComponent(username)}/block`, {
      method: 'POST',
    }),

  unblockUser: (username: string) =>
    request<AdminUserView>(`/api/profiles/admin/users/${encodeURIComponent(username)}/unblock`, {
      method: 'POST',
    }),

  // Returns an outcome rather than throwing when the user cannot be deleted:
  // BLOCKED_INSTEAD is a success, and the caller has to say so.
  deleteUser: (username: string) =>
    request<DeleteUserResult>(`/api/profiles/admin/users/${encodeURIComponent(username)}`, {
      method: 'DELETE',
    }),

  getProfileByUsername: (username: string) =>
    request<AdminUserProfile>(`/api/profiles/${encodeURIComponent(username)}`),

  getOrdersByUsername: (username: string, page = 0, size = 20) =>
    request<PagedResult<OrderResponse>>(
      `/api/shop/users/${encodeURIComponent(username)}/orders?page=${page}&size=${size}`),

  // Product media — presigned upload straight to the storage service's
  // Garage backend, mirroring ui-shop/src/api/storage.ts.
  presignUpload: (fileName: string, contentType: string, scope = 'products') =>
    request<PresignResponse>('/api/storage/presign', {
      method: 'POST',
      body: JSON.stringify({ fileName, contentType, scope }),
    }),

  uploadProductImage: async (file: File, scope = 'products'): Promise<MediaItem> => {
    const presigned = await api.presignUpload(file.name, file.type, scope);
    const putResponse = await fetch(presigned.uploadUrl, {
      method: 'PUT',
      headers: { 'Content-Type': file.type },
      body: file,
    });
    if (!putResponse.ok) {
      throw new Error(`Upload failed: ${putResponse.status} ${putResponse.statusText}`);
    }
    // Never auto-default a freshly uploaded image — the admin picks one explicitly.
    return { key: presigned.key, url: presigned.publicUrl, contentType: file.type, isDefault: false };
  },

  deleteStorageObject: (key: string) =>
    request<void>('/api/storage/objects', { method: 'DELETE', body: JSON.stringify({ key }) }),

  // Registration + password reset — unauthenticated, mirrors ui-shop/src/api/auth.ts.
  register: (body: RegistrationRequest) =>
    request<RegistrationResponse>('/auth/api/register', {
      method: 'POST',
      skipAuth: true,
      body: JSON.stringify(body),
    }),

  requestPasswordReset: (email: string) =>
    request<void>('/auth/api/password-reset/request', {
      method: 'POST',
      skipAuth: true,
      body: JSON.stringify({ email }),
    }),

  confirmPasswordReset: (token: string, newPassword: string) =>
    request<void>('/auth/api/password-reset/confirm', {
      method: 'POST',
      skipAuth: true,
      body: JSON.stringify({ token, newPassword }),
    }),

  // Change password while logged in — mirrors ui-shop/src/api/account.ts.
  changePassword: (body: { currentPassword: string; newPassword: string }) =>
    request<void>('/auth/api/me/password', { method: 'PUT', body: JSON.stringify(body) }),

  // File cabinet — mirrors ui-shop/src/api/profile.ts.
  getFiles: () => request<UserFile[]>('/api/profiles/me/files'),

  checkDuplicateFile: (contentHash: string) =>
    request<DuplicateFileCheckResponse>(`/api/profiles/me/files/duplicate?hash=${encodeURIComponent(contentHash)}`),

  registerFile: (body: {
    key: string; url: string; fileName: string; contentType: string; sizeBytes: number; contentHash: string;
  }) => request<UserFile>('/api/profiles/me/files', { method: 'POST', body: JSON.stringify(body) }),

  // Upload goes straight to storage (same pattern as uploadProductImage above)
  // rather than through a profile-brokered presign — profile only records
  // ownership afterward via registerFile.
  uploadFile: async (file: File): Promise<UserFile> => {
    const contentHash = await sha256Hex(file);

    const dup = await api.checkDuplicateFile(contentHash);
    if (dup.duplicate && dup.existingFile) {
      throw new DuplicateFileError(dup.existingFile);
    }

    const presigned = await api.presignUpload(file.name, file.type, 'user-files');
    const putResponse = await fetch(presigned.uploadUrl, {
      method: 'PUT',
      headers: { 'Content-Type': file.type },
      body: file,
    });
    if (!putResponse.ok) {
      throw new Error(`Upload failed: ${putResponse.status} ${putResponse.statusText}`);
    }
    return api.registerFile({
      key: presigned.key,
      url: presigned.publicUrl,
      fileName: file.name,
      contentType: file.type,
      sizeBytes: file.size,
      contentHash,
    });
  },

  deleteFile: (id: number) => request<void>(`/api/profiles/me/files/${id}`, { method: 'DELETE' }),

  // Balance — mirrors ui-shop/src/api/balance.ts. Top-ups go through payment,
  // because balance never talks to Stripe or PayPal itself.
  getBalance: () => request<BalanceResponse>('/api/balance/me'),

  getBalanceTransactions: (page = 0, size = 20) =>
    request<BalanceTransaction[]>(`/api/balance/me/transactions?page=${page}&size=${size}`),

  transferBalance: (body: TransferRequest) =>
    request<TransferResponse>('/api/balance/me/transfers', {
      method: 'POST',
      body: JSON.stringify(body),
    }),

  // Admin only; a 403 is the server refusing, not the UI.
  giftBalance: (body: { username: string; amountChf: number; reason?: string; idempotencyKey?: string }) =>
    request<GiftResponse>('/api/balance/admin/gifts', {
      method: 'POST',
      body: JSON.stringify(body),
    }),

  createTopupIntent: (amountChf: number, provider: string, currency = 'CHF') =>
    request<CreatePaymentIntentResponse>('/api/payments/topup-intent', {
      method: 'POST',
      body: JSON.stringify({ amount: amountChf, currency, provider }),
    }),

  // The only reliable confirmation for a top-up: provider webhooks resolve
  // payments through an order id, and a top-up has none (finance.md §6.1).
  syncTopup: (paymentId: string) =>
    request<CreatePaymentIntentResponse>(`/api/payments/topup/${paymentId}/sync`, { method: 'POST' }),

  // User-to-user messaging — mirrors ui-shop/src/api/messages.ts. Served by
  // profile under /api/profiles/me/messages, so these are authenticated by the
  // same rule as every other account call above (docs/users/messaging.md).
  getMessages: (box: 'inbox' | 'sent' = 'inbox') =>
    request<MessageResponse[]>(`/api/profiles/me/messages?box=${box}`),

  getMessage: (id: number) => request<MessageResponse>(`/api/profiles/me/messages/${id}`),

  getUnreadMessageCount: () => request<{ count: number }>('/api/profiles/me/messages/unread-count'),

  sendMessage: (body: SendMessageRequest) =>
    request<MessageResponse>('/api/profiles/me/messages', { method: 'POST', body: JSON.stringify(body) }),

  markMessageRead: (id: number) =>
    request<void>(`/api/profiles/me/messages/${id}/read`, { method: 'POST' }),

  deleteMessage: (id: number) =>
    request<void>(`/api/profiles/me/messages/${id}`, { method: 'DELETE' }),

  // Returns nothing for queries under 2 characters — enforced server-side, so a
  // stray single keystroke cannot enumerate the user table.
  searchRecipients: (q: string) =>
    request<RecipientResponse[]>(`/api/profiles/me/messages/recipients?q=${encodeURIComponent(q)}`),
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
