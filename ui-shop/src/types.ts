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
  // Retired from the catalog: hidden from the storefront listing, kept so
  // existing orders still resolve. Only admin listings ask for these.
  discontinued?: boolean;
  /** Null when the product needs no packaging — it already arrived in a box. */
  packagingGroupId?: number | null;
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
  /** How much of `total` is boxes. Zero when nothing needed packaging. */
  packagingTotal?: number;
  /** What the order was packed in — the frozen prices, not today's. */
  packaging?: OrderPackagingResponse[];
}

export interface OrderPackagingResponse {
  groupId: number;
  groupCode: string;
  groupName: string;
  optionId: number;
  optionCode: string;
  optionName: string;
  quantity: number;
  unitPrice: number;
  total: number;
}

/**
 * What boxing a cart would cost, from POST /api/shop/packaging/quote.
 *
 * Every number here is the server's — the client never computes a packaging
 * price, it only sends back the chosen ids.
 */
export interface PackagingQuote {
  packagingRequired: boolean;
  currency: string;
  groups: PackagingGroupQuote[];
}

export interface PackagingGroupQuote {
  groupId: number;
  code: string;
  name: string;
  description?: string;
  units: number;
  options: PackagingOptionQuote[];
}

export interface PackagingOptionQuote {
  optionId: number;
  code: string;
  name: string;
  description?: string;
  imageUrl?: string;
  capacity: number;
  /** Boxes needed for this cart: ceil(units / capacity). */
  packages: number;
  unitPrice: number;
  total: number;
  /** Pre-selected when the shopper expresses no preference. */
  default: boolean;
}

export interface PackagingChoice {
  groupId: number;
  optionId: number;
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
  // Omitted on an edit means "leave as it is", so saving a price change to a
  // discontinued product does not quietly put it back on sale. Send false to
  // restore it, true to retire it.
  discontinued?: boolean;
  /** Same rule: omitted leaves it alone, 0 clears it, an id sets it. */
  packagingGroupId?: number | null;
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
  // The public profile (docs/profile/public-profile.md). `handle` is reserved as
  // soon as it is set, so publicProfile is a plain switch that cannot conflict.
  handle: string | null;
  bio: string | null;
  publicProfile: boolean;
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
  bio?: string;
}

/**
 * What /users/<handle> shows an anonymous visitor. Deliberately not a subset of
 * ProfileResponse — the server sends a different record, so this type must not
 * grow fields by copying that one (docs/profile/public-profile.md step 2).
 *
 * `username` is published on purpose (D3): it is what the Message and Gift
 * actions on that page pass as `to`.
 */
export interface PublicProfileResponse {
  handle: string;
  username: string;
  displayName: string | null;
  avatarUrl: string | null;
  bio: string | null;
  memberSince: string;
}

export interface HandleAvailability {
  handle: string;
  available: boolean;
  reason: string | null;
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
  /**
   * One choice per packaging group in the cart. Required when the quote says
   * `packagingRequired` — the server rejects an order for something that needs a
   * box and names none.
   */
  packaging?: PackagingChoice[];
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

// ── Messaging ───────────────────────────────────────────────────────
// docs/users/messaging.md. The counterparty fields describe the *other*
// party — the sender in the inbox, the recipient in Sent — resolved
// server-side so a list of 20 messages is not 20 profile lookups.
export interface MessageResponse {
  id: number;
  // Null when the message came from the public contact form and the sender was
  // not signed in (docs/users/messaging.md §11) — as is counterpartyUsername,
  // in the recipient's inbox. There is no profile to link to and no inbox to
  // reply into; senderEmail is the only way back to them.
  senderUsername: string | null;
  senderEmail: string | null;
  recipientUsername: string;
  counterpartyUsername: string | null;
  counterpartyDisplayName: string;
  counterpartyAvatarUrl: string | null;
  // Optional: null when the sender did not write one, in which case the
  // list falls back to `preview`.
  subject: string | null;
  body: string;
  preview: string;
  read: boolean;
  readAt: string | null;
  outgoing: boolean;
  createdAt: string;
}

// No email field, deliberately: the search matches on email so a user can
// be found by an address you already know, but returning it would make the
// picker an address-book harvester (docs/users/messaging.md §5).
export interface RecipientResponse {
  username: string;
  displayName: string;
  avatarUrl: string | null;
}

// The public contact form (docs/users/messaging.md §11). No recipient field:
// the server always delivers to the configured manager. `name` and `email` are
// ignored when the caller is signed in — the sender is the JWT subject.
export interface ContactRequest {
  name?: string;
  email?: string;
  subject?: string;
  body: string;
  // The honeypot. Always sent empty by this form; the input is hidden, so
  // anything that fills it in is a bot and the submission is dropped.
  website?: string;
}

export interface ContactResponse {
  status: string;
}

export interface SendMessageRequest {
  // A username or an email address — whichever was typed.
  to: string;
  subject?: string;
  body: string;
}

// ── Balance (docs/finance/finance.md) ───────────────────────────────
// Amounts are rappen in `*Minor` fields; the CHF field is derived
// server-side. A balance can be negative when credit has been extended.
export interface BalanceResponse {
  username: string;
  balanceMinor: number;
  balanceChf: number;
  currency: string;
}

// Signed: negative means money left this account.
export interface BalanceTransaction {
  id: number;
  transferId: string | null;
  amountMinor: number;
  amountChf: number;
  kind: 'TOPUP' | 'SPEND' | 'REFUND' | 'TRANSFER' | 'GIFT';
  reference: string | null;
  memo: string | null;
  createdAt: string;
}

export interface TransferRequest {
  to: string;
  amountChf: number;
  memo?: string;
  // Optional; supply one and a retry replays the original result.
  idempotencyKey?: string;
}

export interface TransferResponse {
  transferId: string;
  from: string;
  to: string;
  amountMinor: number;
  amountChf: number;
}

export interface GiftRequest {
  username: string;
  amountChf: number;
  reason?: string;
  idempotencyKey?: string;
}

export interface GiftResponse {
  transferId: string;
  username: string;
  amountMinor: number;
  amountChf: number;
  grantedBy: string;
}

// ── Treasury (admin) ────────────────────────────────────────────────
export interface AccountView {
  id: number;
  username: string;
  kind: 'USER' | 'HOUSE';
  balanceMinor: number;
  balanceChf: number;
}

export interface LedgerEntryView {
  id: number;
  transferId: string | null;
  accountId: number;
  amountMinor: number;
  amountChf: number;
  kind: 'TOPUP' | 'SPEND' | 'REFUND' | 'TRANSFER' | 'GIFT';
  reference: string | null;
  memo: string | null;
  createdAt: string;
}

/** An account whose cached balance disagrees with the sum of its entries. */
export interface AccountDrift {
  username: string;
  cachedMinor: number;
  ledgerSumMinor: number;
}

/**
 * The central bank's own books. `balanced` is the only field that must ever be
 * true; the rest are the numbers an operator wants (docs/finance/finance.md §7.1).
 */
export interface ReconcileReport {
  balanced: boolean;
  ledgerSumMinor: number;
  userTotalMinor: number;
  houseTotalMinor: number;
  unbackedIssuedMinor: number;
  backedIssuedMinor: number;
  redeemedMinor: number;
  creditOutstandingMinor: number;
  /** Conjured money still sitting in user balances (docs/finance/accounting.md §5). */
  giftedOutstandingMinor: number;
  /** Of everything spent on orders: gift + backed + credit = spend. */
  spentFromGiftMinor: number;
  spentFromBackedMinor: number;
  spentFromCreditMinor: number;
  /** Both must be 0, or the funding split — and therefore contra-revenue — is lying. */
  giftPoolDriftMinor: number;
  fundingSplitViolations: number;
  drift: AccountDrift[];
}

// ── Revenue reports (docs/finance/accounting.md) ──────────────────────
//
// Three owners, three shapes, one page. They are deliberately separate types:
// `house:shop` is balance-paid orders only while the cash view is all orders, so
// the two overlap and neither contains the other. Merging them into one shape is
// the first step towards adding them together, which is the most likely bug on
// this page.

/** shop's cash view: what moved, and when. Not revenue — revenue is recognised on delivery. */
export interface RevenueReport {
  granularity: 'year' | 'month' | 'week';
  currency: string;
  from: string;
  to: string;
  buckets: RevenueBucket[];
  totals: {
    grossMinor: number;
    refundedMinor: number;
    netMinor: number;
    orderCount: number;
    refundCount: number;
    returnsPendingMinor: number;
  };
}

export interface RevenueBucket {
  bucket: string;
  label: string;
  grossMinor: number;
  refundedMinor: number;
  netMinor: number;
  orderCount: number;
  refundCount: number;
  /** Requested but not yet settled. Shown beside gross, never subtracted from it. */
  returnsPendingMinor: number;
}

/** balance's money creation and where the spend came from. CHF only — no currency selector. */
export interface MoneySupplyReport {
  granularity: string;
  from: string;
  to: string;
  buckets: MoneySupplyBucket[];
  totals: {
    giftedMinor: number;
    toppedUpMinor: number;
    spentMinor: number;
    refundedMinor: number;
    spentFromGiftMinor: number;
    spentFromBackedMinor: number;
    spentFromCreditMinor: number;
    /** Conjured money still held: the disclosed figure standing in for a liability we do not book. */
    giftedOutstandingMinor: number;
  };
}

export interface MoneySupplyBucket {
  bucket: string;
  label: string;
  giftedMinor: number;
  toppedUpMinor: number;
  spentMinor: number;
  refundedMinor: number;
  spentFromGiftMinor: number;
  spentFromBackedMinor: number;
  spentFromCreditMinor: number;
}

/** accounting's accrual view: what we earned, as booked. */
export interface AccrualReport {
  granularity: string;
  currency: string;
  booksOpenedOn: string;
  from: string;
  to: string;
  buckets: AccrualBucket[];
  /** Outside totals on purpose: a position as of a date, never netted against revenue. */
  creditLoss: CreditLossReport;
  totals: {
    revenueGrossMinor: number;
    contraGiftMinor: number;
    contraReturnsMinor: number;
    netRevenueMinor: number;
    deliveredCount: number;
  };
}

export interface AccrualBucket {
  bucket: string;
  label: string;
  periodStatus: 'OPEN' | 'CLOSED';
  revenueGrossMinor: number;
  contraGiftMinor: number;
  contraReturnsMinor: number;
  netRevenueMinor: number;
  deliveredCount: number;
}

export interface CreditLossReport {
  asOf: string;
  /** Always true. The UI keys its "assumption, not measurement" styling off it. */
  estimated: boolean;
  exposureMinor: number;
  allowanceMinor: number;
  bands: CreditLossBand[];
}

export interface CreditLossBand {
  maxAgeDays: number | null;
  lossRate: number;
  exposureMinor: number;
  allowanceMinor: number;
}
