// Mirrors the shop service contracts used by ui-shop (see ui-shop/src/types.ts).
export interface MediaItem {
  key: string;
  url: string;
  contentType: string;
  isDefault: boolean;
}

export interface Product {
  id: number;
  name: string;
  description: string;
  price: number;
  stock: number;
  categoryId: number;
  imageUrl: string;
  // Optional (not required) so FALLBACK_PRODUCTS (editorial, client-only
  // entries with negative ids) don't need a media field at all.
  media?: MediaItem[];
}

export interface Category {
  id: number;
  name: string;
  description: string;
}

export interface CreateProductRequest {
  name: string;
  description: string;
  price: number;
  stock: number;
  categoryId: number;
  imageUrl: string;
  media: MediaItem[];
}

export interface PresignResponse {
  key: string;
  uploadUrl: string;
  publicUrl: string;
  expiresIn: number;
}

export interface OrderItemResponse {
  id: number;
  productId: number;
  productName: string;
  quantity: number;
  unitPrice: number;
}

export interface DeliveryAddress {
  recipientName: string;
  addressLine1: string;
  addressLine2?: string;
  city: string;
  state?: string;
  zipCode: string;
  country: string;
}

export interface OrderResponse {
  id: number;
  username: string;
  status: string;
  total: number;
  createdAt: string;
  items: OrderItemResponse[];
  clientSecret?: string;
  address?: DeliveryAddress;
}

export interface PlaceOrderRequest {
  items: { productId: number; quantity: number }[];
  address: DeliveryAddress;
}

export interface CreatePaymentIntentResponse {
  id: string;
  orderId: number;
  stripePaymentIntentId: string;
  clientSecret: string;
  status: string;
  amount: number;
  currency: string;
  createdAt: string;
}

export interface AddressResponse {
  id: number;
  label?: string;
  recipientName: string;
  addressLine1: string;
  addressLine2?: string | null;
  city: string;
  state?: string | null;
  zipCode: string;
  country: string;
  isDefault: boolean;
}

export interface AddressRequest {
  label?: string;
  recipientName: string;
  addressLine1: string;
  addressLine2?: string;
  city: string;
  state?: string;
  zipCode: string;
  country: string;
  isDefault?: boolean;
}

export type AvatarSource = 'UPLOAD' | 'GOOGLE' | 'NONE';

export interface ProfileResponse {
  id: number;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  displayName: string | null;
  // The effective picture, already resolved from avatarSource server-side —
  // render this one. The other two exist so the profile page can offer a
  // choice without a second round trip (docs/users/user-pic.md §4).
  avatarUrl: string | null;
  avatarSource: AvatarSource;
  uploadedAvatarUrl: string | null;
  googlePictureUrl: string | null;
}

export interface UpdateProfileRequest {
  email: string;
  firstName: string;
  lastName: string;
  displayName?: string;
}

export interface PagedResult<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export interface RegistrationRequest {
  username: string;
  email: string;
  password: string;
  firstName?: string;
  lastName?: string;
}

export interface RegistrationResponse {
  username: string;
  email: string;
}

// ── Admin user management ───────────────────────────────────────────
// Built from auth-server users, not profiles: the two are not the same
// set — a profile row can belong to a Google subject or a service
// account, and a real user may have no profile row at all.
// See docs/users/blocking-users.md §2.1. Mirrors ui-shop/src/types.ts.
export interface AdminUserView {
  username: string;
  email: string;
  firstName: string | null;
  lastName: string | null;
  displayName: string | null;
  enabled: boolean;
  // LOCAL — form login. LINKED — registered locally, later signed in with
  // Google; their password still works. GOOGLE — no password at all.
  signInState: 'LOCAL' | 'LINKED' | 'GOOGLE';
  roles: string[];
  hasProfile: boolean;
  avatarUrl: string | null;
  blockedAt: string | null;
  blockedBy: string | null;
  profileCreatedAt: string | null;
}

export interface AdminUserProfile {
  id: number;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  avatarUrl: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface DeleteUserResult {
  // BLOCKED_INSTEAD is a successful outcome, not a failure: a user with
  // paid orders can only be blocked (docs/users/blocking-users.md D1).
  outcome: 'DONE' | 'BLOCKED_INSTEAD';
  paidOrderCount: number;
  deletedOrderCount: number;
}

export interface UserFile {
  id: number;
  fileName: string;
  url: string;
  contentType: string;
  sizeBytes: number | null;
  createdAt: string;
}

export interface DuplicateFileCheckResponse {
  duplicate: boolean;
  existingFile: UserFile | null;
}
