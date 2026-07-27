# User profile page refactor — split into dedicated pages

Status: **not started** · Last updated: 2026-07-28

Goal: the "My Profile" page in both `ui-shop` and `ui-demo` has grown to own
personal details, password change, the file cabinet, and (in `ui-demo`) the
address book, all in one component. Split each concern into its own
route/page, leaving Profile as a slim hub that links out to the others —
matching the single-responsibility precedent `ui-shop` already set with its
standalone `/orders` and `/addresses` pages.

---

## Current state

### `ui-shop`

`src/pages/Profile.tsx` (283 lines) owns, in one component: personal-details
edit form, a **Password** section (`handleChangePassword` + state), a
**Files** section (`handleFileSelected`/`handleDeleteFile`/`handleCopyLink` +
state), and a **Quick Links** block linking out to the *already-standalone*
`/orders` (`pages/Orders.tsx`) and `/addresses` (`pages/Addresses.tsx`) pages.
Those two are the precedent this refactor extends to Password and Files —
they're plain top-level routes registered in `src/App.tsx`, styled with the
existing `.page`/`.btn`/`.btn-primary` CSS classes, nothing framework-specific
about them.

### `ui-demo`

`src/pages/ProfilePage.tsx` (673 lines) is worse off: it inlines **four**
concerns as private sub-components — `ProfileDetails` (:86), `PasswordSection`
(:219), `FilesSection` (:311), `AddressBook` (:436) — all rendered on one route
(`/profile`) with no standalone address page today, and **no orders page at all**
(order history doesn't exist yet in `ui-demo` — out of scope for this refactor,
not being added here).

Helpfully, all four take **no props** and fetch their own data, so promoting them
really is a cut/paste + export-rename. The catch is the auth gate — see below.

---

## The one thing that isn't a move: the auth guard

**Neither app protects `/profile` at the route level.** The guard lives in the
parent component this refactor is *keeping*, so every new page would render for
anonymous visitors and fire authenticated calls that 401:

- `ui-shop` — `Profile.tsx:50-53`, inside the same `useEffect` that loads the
  profile: `if (!isAuthenticated) { navigate('/login', { replace: true }); return; }`
- `ui-demo` — `ProfilePage.tsx:39-65`: an `authLoading` spinner plus the full
  "Your Account Awaits" sign-in CTA. `PasswordSection`, `FilesSection` and
  `AddressBook` contain none of it, so that CTA would silently vanish.

**Do not copy the guard into five new pages.** Extract it once per app, and let
the existing pages use it too:

```tsx
// ui-shop/src/components/RequireAuth.tsx
export default function RequireAuth() {
  const { isAuthenticated, loading } = useAuth();
  const location = useLocation();
  if (loading) return <div className="page"><div className="spinner" style={{ margin: '0 auto' }} /></div>;
  if (!isAuthenticated) return <Navigate to="/login" replace state={{ from: location }} />;
  return <Outlet />;
}
```

wrapped around the account routes as a pathless route:

```tsx
<Route element={<RequireAuth />}>
  <Route path="profile" element={<Profile />} />
  <Route path="profile/password" element={<Password />} />
  <Route path="profile/files" element={<Files />} />
  <Route path="addresses" element={<Addresses />} />
</Route>
```

`ui-demo` gets the same thing, except its unauthenticated branch renders the
existing sign-in CTA block (moved out of `ProfilePage.tsx` verbatim) instead of
redirecting — that's the current behaviour and it's better than a redirect for a
storefront.

This is net-new (no shared guard exists today — `Orders.tsx:14`, `Checkout.tsx:176`
and `ProfilePage.tsx` each roll their own), but it's less total code than the
status quo and it's the moment to do it. If you'd rather not, the fallback is
copying the four-line `useEffect` guard into each new page — but then say so
explicitly, because forgetting it on one page is silent.

---

## Decisions taken up front

| Question | Decision | Why |
| --- | --- | --- |
| Persistent tab bar vs. hub page with links? | **Hub page with links** ("Quick Links"-style), not a persistent account sub-nav. | `ui-shop` already does this for Orders/Addresses — extending the same pattern to Password/Files is zero new UI concepts, just more links. A tab bar is a reasonable future upgrade but is new surface area this refactor doesn't need. |
| Add an orders page to `ui-demo`? | **No.** | Not requested, and `ui-demo` has no order-history feature today — out of scope. |
| New API/type changes? | **None.** | This is a pure client-side page-structure reorganization. `api.ts`/`api/*.ts` and `types.ts` in both apps are untouched — every new page calls the exact same functions the inline sections already call. |
| Backend/infra changes? | **None.** | Same reasoning as above. |
| Nested (`/profile/password`) or top-level (`/password`) routes? | **Nested under `/profile`.** | Better grouping, and it keeps the account area obvious in `App.tsx`. Note this *diverges* from the Orders/Addresses precedent, which is top-level (`App.tsx:34-38`) — see below. |
| Move `ui-shop`'s `/addresses` under `/profile` too? | **No.** | Out of scope and it would need a redirect for existing links/bookmarks. Accepted consequence: `ui-shop` keeps `/addresses` while `ui-demo` gets `/profile/addresses`, so the same feature sits at different URLs in the two apps. Worth unifying later, in one pass, with redirects. |
| Guard the new routes how? | **A shared `RequireAuth` wrapper per app.** | The guard currently lives in the parent being kept — see the section below. This is the one part of the refactor that is not a move. |

---

## `ui-shop`

New pages, split straight out of `Profile.tsx`, following `Addresses.tsx`'s
existing standalone-page conventions (`.page` wrapper, `.btn`/`.btn-primary`,
own `loading`/`busy`/error-message state):

| File | Contents (moved verbatim from `Profile.tsx`) |
| --- | --- |
| `src/pages/Password.tsx` (new) | `currentPassword`/`newPassword`/`confirmPassword` state, `handleChangePassword`, its form + error/success messaging. **Not a verbatim move — see below.** |
| `src/pages/Files.tsx` (new) | `files`/`filesLoading`/`uploading`/`fileError`/`duplicateFile` state, `handleFileSelected`/`handleDeleteFile`/`handleCopyLink`, `ALLOWED_FILE_TYPES`/`MAX_FILE_SIZE_BYTES`/`formatSize`, the upload input + file list. |

**The Password page has a hidden dependency on `profile`.**
`handleChangePassword` reads `profile?.email` (`Profile.tsx:135`) to choose
between "Password changed. A confirmation email was sent to your address." and a
bare "Password changed." On a standalone page there is no `profile` object.

Resolve it by making the message unconditional rather than adding a whole
`getProfile()` round-trip for one sentence:

> "Password changed. If your account has an email address, a confirmation was sent."

(`ui-demo`'s `PasswordSection` has no such coupling — it is genuinely
self-contained. The two apps differ here; don't assume symmetry.)

`src/pages/Profile.tsx` shrinks to: the personal-details edit form only, plus
its existing **Quick Links** section extended with two more entries:

```tsx
<Link to="/profile/password" className="btn" style={{ textAlign: 'center' }}>Password</Link>
<Link to="/profile/files" className="btn" style={{ textAlign: 'center' }}>Files</Link>
<Link to="/orders" className="btn" style={{ textAlign: 'center' }}>My Orders</Link>
<Link to="/addresses" className="btn" style={{ textAlign: 'center' }}>My Addresses</Link>
```

Also strip from `Profile.tsx` everything the split leaves behind: the password
and file state, `fileInputRef`, `handleChangePassword`/`handleFileSelected`/
`handleDeleteFile`/`handleCopyLink`, the `ALLOWED_FILE_TYPES`/
`MAX_FILE_SIZE_BYTES`/`formatSize` constants, the `api.profile.getFiles()` call
in the mount effect, and the now-unused imports. `tsc -b` only flags these if
`noUnusedLocals` is enabled — check before relying on the build to catch them.

`src/App.tsx` — add the two routes inside the new `RequireAuth` wrapper (see the
auth-guard section), alongside the existing `profile`/`addresses` entries:

```tsx
<Route element={<RequireAuth />}>
  <Route path="profile" element={<Profile />} />
  <Route path="profile/password" element={<Password />} />
  <Route path="profile/files" element={<Files />} />
  <Route path="addresses" element={<Addresses />} />
</Route>
```

## `ui-demo`

Same split, applied to `ProfilePage.tsx`'s four inline sub-components. Since
`ProfileDetails`, `PasswordSection`, `FilesSection`, and `AddressBook` are
already self-contained functions (own state, own effects), promoting them to
top-level page components is mostly a cut/paste + export-rename, not a
rewrite:

| File | Contents (promoted from `ProfilePage.tsx`) |
| --- | --- |
| `src/pages/PasswordPage.tsx` (new) | today's `PasswordSection` component, wrapped in the same `bg-ivory pt-28 lg:pt-32` / `mx-auto max-w-3xl` page shell `ProfilePage.tsx` uses. |
| `src/pages/FilesPage.tsx` (new) | today's `FilesSection` component, same page shell. |
| `src/pages/AddressesPage.tsx` (new) | today's `AddressBook` component, same page shell — this is the one net-new standalone page, since `ui-demo` has no separate addresses route today. |

`src/pages/ProfilePage.tsx` shrinks to: `ProfileDetails` (personal info incl.
`displayName`) plus a new **Quick Links**-style section (new to `ui-demo`,
mirroring `ui-shop`'s existing pattern):

```tsx
<Link to="/profile/password" className="border border-cocoa px-8 py-3 text-xs uppercase tracking-[0.18em] text-cocoa text-center transition-colors duration-300 hover:bg-cocoa hover:text-ivory">Password</Link>
<Link to="/profile/files" className="...">Files</Link>
<Link to="/profile/addresses" className="...">Addresses</Link>
```

`ProfilePage.tsx` also sheds its unauthenticated CTA block (lines 39-65), which
moves into `RequireAuth` so all four account pages share it.

`src/App.tsx` — add three routes, wrapped in the guard, next to the existing
`profile` entry:

```tsx
<Route element={<RequireAuth />}>
  <Route path="profile" element={<ProfilePage />} />
  <Route path="profile/password" element={<PasswordPage />} />
  <Route path="profile/files" element={<FilesPage />} />
  <Route path="profile/addresses" element={<AddressesPage />} />
</Route>
```

No orders route — not in scope (see decisions table above).

---

## Out of scope

- Adding order history to `ui-demo`.
- A persistent account sub-nav/tab bar (worth a future revisit, not part of
  this refactor).
- Any change to `api.ts`/`api/*.ts`, `types.ts`, or any backend/infra —
  every new page reuses the exact same API calls the inline sections already
  make today.

## Verification

- `cd ui-shop && npx tsc -b && npm run build`
- `cd ui-demo && npx tsc -b && npm run build`
- Manual: from `/profile` in each app, follow each Quick Link to its new page
  and exercise it (change password, upload/duplicate-detect/delete a file,
  add/edit/delete an address) — behavior should be identical to today's
  inline sections, just on their own routes.
- **Signed out, hit every new URL directly** (`/profile/password`,
  `/profile/files`, `/profile/addresses`). `ui-shop` must redirect to `/login`;
  `ui-demo` must show the "Your Account Awaits" CTA. This is exactly what a
  missed auth guard breaks, and no build or type-check will catch it.
- Confirm no page fires an authenticated API call before the guard resolves —
  watch the network tab while loading each route logged out.
