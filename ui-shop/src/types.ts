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
  media: MediaItem[];
}

export interface Category {
  id: number;
  name: string;
  description: string;
}

export interface OrderItemResponse {
  id: number;
  productId: number;
  productName: string;
  quantity: number;
  unitPrice: number;
}

export interface OrderResponse {
  id: number;
  username: string;
  status: string;
  total: number;
  currency: string;
  createdAt: string;
  items: OrderItemResponse[];
  provider?: string;
  providerPayload?: ProviderPayload;
  /** @deprecated use providerPayload.clientSecret. Server always sends null. */
  clientSecret?: string;
  address?: DeliveryAddress;
}

export interface RefundInfo {
  providerRefundId: string | null;
  amount: number;
  status: string;
  createdAt: string;
  /** @deprecated use providerRefundId. */
  stripeRefundId: string | null;
}

/**
 * Whatever the provider needs to complete the payment in the browser. Its shape
 * depends on the provider's confirmation mode — a client secret for CLIENT_SDK, a
 * redirect URL for REDIRECT — so never assume a field is present.
 */
export interface ProviderPayload {
  clientSecret?: string;
  redirectUrl?: string;
  [key: string]: unknown;
}

/** How the browser completes a payment. Widgets switch on this, not on provider id. */
export type ConfirmationMode = 'CLIENT_SDK' | 'REDIRECT';

export interface PaymentProviderInfo {
  id: string;
  displayName: string;
  confirmationMode: ConfirmationMode;
  webhookEnabled: boolean;
}

export interface CreatePaymentIntentResponse {
  id: string;
  orderId: number;
  provider: string;
  providerPaymentId: string;
  providerPayload?: ProviderPayload | null;
  status: string;
  amount: number;
  currency: string;
  createdAt: string;
  refund?: RefundInfo | null;
  /** @deprecated use providerPaymentId. */
  stripePaymentIntentId: string;
  /** @deprecated use providerPayload.clientSecret. */
  clientSecret: string;
}

export interface PagedResult<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
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

export interface CreateCategoryRequest {
  name: string;
  description: string;
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

export type AvatarSource = 'UPLOAD' | 'GOOGLE' | 'NONE';

export interface ProfileResponse {
  id: number;
  username: string;
  email: string | null;
  firstName: string | null;
  lastName: string | null;
  displayName: string | null;
  // The effective picture, already resolved from avatarSource server-side —
  // render this one. The other two exist so the profile page can offer a
  // choice without a second round trip (docs/users/user-pic.md §4).
  avatarUrl: string | null;
  avatarSource: AvatarSource;
  uploadedAvatarUrl: string | null;
  googlePictureUrl: string | null;
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

export interface UpdateProfileRequest {
  email?: string;
  firstName?: string;
  lastName?: string;
  displayName?: string;
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


export interface AddressResponse {
  id: number;
  label: string | null;
  recipientName: string;
  addressLine1: string;
  addressLine2: string | null;
  city: string;
  state: string | null;
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

export interface DeliveryAddress {
  recipientName: string;
  addressLine1: string;
  addressLine2?: string;
  city: string;
  state?: string;
  zipCode: string;
  country: string;
}

export interface PlaceOrderRequest {
  items: { productId: number; quantity: number }[];
  address: DeliveryAddress;
  /** Omitted while only one provider is enabled; required once several are. */
  provider?: string;
}

export interface CartItem {
  product: Product;
  quantity: number;
}

export interface TrackingEvent {
  status: string;
  timestamp: string;
  description: string;
}

export interface DeliveryResponse {
  id: string;
  orderId: number;
  status: string;
  paymentStatus: string;
  items: string | null;
  recipientName: string;
  addressLine1: string;
  addressLine2: string | null;
  city: string;
  state: string | null;
  zipCode: string;
  country: string;
  estimatedDeliveryDate: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TrackingDetailResponse {
  deliveryId: string;
  orderId: number;
  currentStatus: string;
  paymentStatus: string;
  items: string | null;
  estimatedDelivery: string | null;
  events: TrackingEvent[];
}

// ── Admin user management ───────────────────────────────────────────
// Built from auth-server users, not profiles: the two are not the same
// set — a profile row can belong to a Google subject or a service
// account, and a real user may have no profile row at all.
// See docs/users/blocking-users.md §2.1.
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

export interface DeleteUserResult {
  // BLOCKED_INSTEAD is a successful outcome, not a failure: a user with
  // paid orders can only be blocked (docs/users/blocking-users.md D1).
  outcome: 'DONE' | 'BLOCKED_INSTEAD';
  paidOrderCount: number;
  deletedOrderCount: number;
}
