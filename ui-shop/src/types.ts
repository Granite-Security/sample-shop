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
  createdAt: string;
  items: OrderItemResponse[];
  clientSecret?: string;
  address?: DeliveryAddress;
}

export interface RefundInfo {
  stripeRefundId: string | null;
  amount: number;
  status: string;
  createdAt: string;
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
  refund?: RefundInfo | null;
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

export interface ProfileResponse {
  id: number;
  username: string;
  email: string | null;
  firstName: string | null;
  lastName: string | null;
  displayName: string | null;
}

export interface AdminUserProfile {
  id: number;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
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

export interface PresignFileResponse {
  key: string;
  uploadUrl: string;
  publicUrl: string;
  expiresIn: number;
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
