# UI Improvement Suggestions

## Current State

The payment flow works end-to-end but has rough edges:

- `pollOrderStatus` times out after 30s and shows "done" regardless of actual order status
- The order detail page shows PENDING until the Kafka async flow completes
- No visual indication of payment success vs. order fulfillment state
- Checkout success screen is minimal (just "done")
- No inline validation feedback on Stripe PaymentElement errors

## Improvements

### 1. Fix poll timeout — check payment status as fallback

**File:** `Checkout.tsx`, `pollOrderStatus`

On timeout (30s), instead of blindly setting `step = 'done'`, check:
1. `GET /api/payments/intent/{orderId}` — is the payment SUCCEEDED?
2. If yes → show "Payment confirmed, waiting for order to finalize…" text + keep polling
3. If no → show failure

This prevents false success when the payment was successful but Kafka/shop processing is slow.

### 2. Show order status after payment success

**File:** `Checkout.tsx`, done/confirming sections

When `step === 'done'` or `step === 'confirming'`, show:
- ✅ Payment status (from payment endpoint)
- ⌛ Order status (from order endpoint)
- Estimated wait time

This gives the user transparency into what's happening.

### 3. Redirect support for 3D Secure cards

**File:** `Checkout.tsx`

Some cards (e.g. `4000002500003155`) trigger a 3D Secure redirect. Currently:
1. `confirmPayment` with `redirect: 'if_required'` would redirect the browser
2. The return URL is `window.location.origin + /orders/{orderId}`
3. But there's no route/page handling the redirect return flow

**Solution:** Add a `PaymentReturn` component that:
- Reads `payment_intent` and `payment_intent_client_secret` from URL query params
- Calls `stripe.retrievePaymentIntent(clientSecret)` to get status
- If succeeded → calls sync endpoint + polls order status
- If failed → shows error

Or simpler: handle it in `Checkout.tsx` by checking `window.location.search` on mount for Stripe redirect params.

### 4. Order detail page — richer info

**File:** `OrderDetail.tsx`

- Show payment status alongside order status (call `GET /api/payments/intent/{orderId}`)
- Show Stripe PaymentIntent ID (for support/debugging)
- Add a "Copy Order ID" button
- Show estimated delivery timeframe
- Add a "Track Order" section if shipment is dispatched

### 5. Checkout — better loading states

**File:** `Checkout.tsx`

- `review` step: disable "Place Order" if cart is empty, add quantity change
- `placing` step: add progress bar or step indicator
- `waiting_payment` step: show animated dots, estimated remaining time
- `payment` step: highlight the payment form, show amount prominently
- `confirming` step: show spinner with "Payment confirmed — finalizing your order…"
- `done` step: animated success checkmark, order summary, "View Order" button
- `failed` step: specific error recovery actions (retry, contact support)

### 6. Order list page — filtering and sorting

**File:** `Orders.tsx`

- Add status filter (PENDING, PAID, SHIPPED, DELIVERED, CANCELED)
- Add date range picker
- Sort by date (newest first by default)
- Pagination if > 20 orders
- Show payment status icon per order row

### 7. Cart — quantity selector improvements

**File:** `Cart.tsx`

- Add +/- buttons next to quantity (currently shows input field)
- Show stock availability
- Prevent ordering quantity > stock
- Show "Free shipping" banner if applicable
- Auto-save quantity changes (optimistic update)

### 8. Global — toast notifications

**File:** `App.tsx` or new `ToastContext`

- Replace inline error messages with toast notifications
- Show success toasts for: order placed, payment confirmed, etc.
- Show error toasts with auto-dismiss
- Use CSS transitions for enter/exit

### 9. Mobile responsive checkout

**File:** `Checkout.tsx` CSS

- Stack items vertically on mobile
- Full-width payment form
- Larger touch targets for buttons
- Bottom-fixed "Place Order" / "Pay Now" button on mobile

### 10. Home page — order status summary

**File:** `Home.tsx`

- Show "You have X pending orders" banner for authenticated users
- Quick-action cards: View Orders, Continue Shopping
- Show last order status

### 11. Payment testing helper (dev only)

**File:** Conditionally rendered when `VITE_STRIPE_PUBLISHABLE_KEY` starts with `pk_test_`

- Show test card numbers (4242…, 4000002500003155…, etc.)
- Show test CVC/CVV hints
- Show "Test mode" badge in corner
- Allow simulating different payment outcomes

### 12. Error boundary

**File:** `src/components/ErrorBoundary.tsx`

- Wrap the app in an error boundary
- Show a friendly fallback UI instead of a blank page
- Offer a "Reload" button and link to support

## Priority Order

1. **Phase A (critical)** — Fix poll timeout fallback + show real status
2. **Phase B (high)** — Redirect support (3DS cards), payment info in order detail
3. **Phase C (medium)** — Toast notifications, better loading states, error boundary
4. **Phase D (low)** — Mobile responsive, cart improvements, order list filtering
5. **Phase E (nice-to-have)** — Testing helper, home page summary, "Copy Order ID"

## Implementation Notes

- Most changes are in `ui-shop/src/` only — no backend modifications needed
- New components should follow existing conventions (functional components, CSS modules or inline styles matching current patterns)
- Test mode helper should be gated on `import.meta.env.VITE_STRIPE_PUBLISHABLE_KEY?.startsWith('pk_test_')`
- Avoid adding new dependencies — Stripe SDK (`@stripe/react-stripe-js`, `@stripe/stripe-js`) is the only payment-related dependency
