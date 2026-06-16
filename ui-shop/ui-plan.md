# UI Shop — Implementation Plan

**Stack**: React 19 + TypeScript + Vite + React Router.

**API base**: `http://localhost:8080` (gateway proxies `/api/shop/*` to the shop service).

## API Overview (from `/v3/api-docs`)

| Endpoint | Auth | Description |
|---|---|---|
| `GET /api/shop/categories` | Public | List categories |
| `POST /api/shop/categories` | ADMIN | Create category |
| `PUT /api/shop/categories/{id}` | ADMIN | Update category |
| `DELETE /api/shop/categories/{id}` | ADMIN | Delete category |
| `GET /api/shop/products` | Public | List products |
| `GET /api/shop/products/{id}` | Public | Get product detail |
| `POST /api/shop/products` | ADMIN | Create product |
| `PUT /api/shop/products/{id}` | ADMIN | Update product |
| `DELETE /api/shop/products/{id}` | ADMIN | Delete product |
| `GET /api/shop/orders` | JWT | List my orders |
| `POST /api/shop/orders` | JWT | Place order |
| `GET /api/shop/orders/{id}` | JWT | Get order by ID |
| `GET /api/shop/greetings` | Public | Greeting |

## Tasks

### Phase 1 — Foundation

- [ ] **1.1 — Project structure & routing**
  - Install `react-router` (and `@types/react-router` if needed — v7 ships its own types).
  - Set up `BrowserRouter` with routes: `/`, `/catalog`, `/catalog/:id`, `/cart`, `/orders`, `/orders/:id`, `/admin`, `/admin/products`, `/admin/categories`, `/login`, `/callback`.
  - Create page shells for each route.
  - Create a shared `Layout` component with `<Outlet />` for nested rendering.

- [ ] **1.2 — HTTP client layer**
  - Create an `api.ts` utility with `fetch` wrappers for all endpoints.
  - All requests use `credentials: 'include'` to send the gateway session cookie.
  - API base: `http://localhost:8080` (gateway CORS allows `localhost:5173`).
  - On 401: redirect full-page to the gateway's OAuth2 login endpoint.

- [ ] **1.3 — Auth / token management**
  - The gateway uses server-side sessions (cookie-based). The SPA never receives a JWT — the gateway relays it to downstream services via `TokenRelay`.
  - `AuthProvider` calls `GET /api/user/me` on mount to check if a session cookie exists.
  - Returns the user's `name` (from `preferred_username` or `sub`) and `claims` (includes `roles`).
  - `useAuth()` exposes `isAuthenticated`, `isAdmin`, `user`, `logout()`, `loading`.

- [ ] **1.4 — Login flow**
  - **Login page**: redirects full-page to `http://localhost:8080/oauth2/authorization/oidc-client`.
  - **Gateway**: configured with a custom `ServerAuthenticationSuccessHandler` that redirects to `http://localhost:5173/` after successful OAuth2 login.
  - **Logout**: navigates to `http://localhost:9090/logout` to clear the auth-server session.
  - **Gateway change**: added `UserController` (`GET /api/user/me`) and `app.spa-origin` property.
  - **No callback page needed** — the gateway handles the OAuth2 code exchange server-side.

### Phase 2 — Public Catalog

- [ ] **2.1 — Category browsing**
  - Fetch and display categories from `GET /api/shop/categories`.
  - Show as a sidebar/filter list; clicking a category filters products.

- [ ] **2.2 — Product listing**
  - Fetch and display products from `GET /api/shop/products`.
  - Grid/list view with product cards (image placeholder, name, price, stock badge).
  - Filter by category, search by name.

- [ ] **2.3 — Product detail page**
  - Fetch single product from `GET /api/shop/products/{id}`.
  - Show full product info (name, description, price, stock, category).
  - "Add to cart" button (disabled if out of stock).

### Phase 3 — Shopping Cart & Orders

- [ ] **3.1 — Cart state management**
  - Client-side cart using React context + `localStorage` persistence.
  - Cart items: product + quantity.
  - Cart drawer / page with item list, quantity controls, total.

- [ ] **3.2 — Place order (authenticated)**
  - Checkout page: review cart items, confirm order.
  - `POST /api/shop/orders` with cart payload.
  - On success: clear cart, navigate to order confirmation.

- [ ] **3.3 — Order history (authenticated)**
  - `GET /api/shop/orders` — list user's orders with status, total, date.
  - Click an order to see details (`GET /api/shop/orders/{id}`).

### Phase 4 — Admin

- [ ] **4.1 — Admin dashboard**
  - Guarded by role check (`ROLE_ADMIN` from JWT).
  - Overview with quick stats (total products, categories, orders).

- [ ] **4.2 — Product management (CRUD)**
  - Table listing all products with edit/delete actions.
  - Create/Edit form (name, description, price, stock, category dropdown).
  - Delete with confirmation dialog.

- [ ] **4.3 — Category management (CRUD)**
  - Table listing categories with edit/delete.
  - Create/Edit form (name).
  - Delete with confirmation.

### Phase 5 — Polish

- [ ] **5.1 — Error handling & loading states**
  - Consistent loading spinners / skeleton screens.
  - Error banners with retry.

- [ ] **5.2 — Responsive design**
  - Mobile-friendly layout (stacked cards, hamburger nav).

- [ ] **5.3 — API spec integration**
  - Optionally generate TypeScript types from the OpenAPI spec using `openapi-typescript`.

---

**Auth flow (implemented)**:
1. SPA redirects to `http://localhost:8080/oauth2/authorization/oidc-client`
2. Gateway forwards to auth-server login page
3. User authenticates (form login or Google)
4. Gateway exchanges auth code for tokens, creates server-side session
5. Gateway's custom success handler redirects to `http://localhost:5173/`
6. SPA loads, calls `GET /api/user/me` with the session cookie to get user info
7. All subsequent API calls include the session cookie via `credentials: 'include'`
8. The gateway's `TokenRelay` filter attaches the JWT Bearer token when proxying to downstream services

**Gateway files added/modified**:
- `controller/UserController.java` — `GET /api/user/me` returns `{authenticated, name, claims}`
- `config/GateSec.java` — custom OAuth2 success handler redirects to SPA; `/api/user/me` is permitAll
