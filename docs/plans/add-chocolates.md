# Make sichocolate.com's showcased products actually orderable

## Symptom

On `sichocolate.com`, adding a product to the cart and checking out shows:

> These pieces are from the editorial preview catalog and can't be ordered —
> the shop backend isn't reachable.

even though the shop backend *is* reachable — `/api/shop/products` and
`/api/shop/categories` both respond fine. The message is misleading; the real
condition (`ui-demo/src/pages/CheckoutPage.tsx`) is "every item in the cart has
a negative id," i.e. every item came from the client-side fallback catalog, not
the database.

## Root cause

`ui-demo/src/store.tsx`'s `refresh()` fetches the live catalog, filters to
whichever category name/description matches `/choco|sweet|confection|food/i`
(today: `Food & Sweets`, category id 4), and tops the grid up to 8 items using
`FALLBACK_PRODUCTS` (`ui-demo/src/api.ts`, ids `-1` through `-8`) whenever the
live category has fewer than 8 products.

`Food & Sweets` currently has exactly **5** products, seeded by
`shop/src/main/resources/db/changelog/002-seed-products.sql`:
Dark Chocolate Bar, Milk Chocolate Bar, Truffle Collection Box, Hazelnut
Chocolate, White Chocolate Truffles. None of these match the **8** curated,
on-brand pieces `FALLBACK_PRODUCTS` actually markets (Ecuador 72%
Single-Origin Bar, Sea Salt Caramel Truffles, Madagascar 85% Intense, The
Signature Gift Box, Pistachio & Rose Praline, Ghana 65% Velvet Bar, Hot
Chocolate Flakes, Espresso Ganache Collection).

So every visit shows 5 real (orderable) products plus 3 fallback
(un-orderable) ones to fill the grid to 8 — and since the fallback pieces are
the ones actually reflecting the sichocolate.com brand story, customers
naturally reach for those first, hit the negative-id checkout guard, and see
the error above.

**This is the same backend/DB `ui-shop` uses** (see `sichocolate.md` §0) — any
fix here must not remove or rename the existing 5 products, since `ui-shop`'s
general storefront lists the same `Food & Sweets` category.

## Plan

### 1. Seed the 8 curated products for real — new Liquibase changeset

Add `shop/src/main/resources/db/changelog/005-seed-choco-products.sql`,
included from `db.changelog-master.yaml` after `004-add-order-address.sql`.

- Insert the 8 `FALLBACK_PRODUCTS` entries as real `product` rows under the
  existing `Food & Sweets` category (resolved by name, same
  `(SELECT id FROM category WHERE name = 'Food & Sweets')` pattern `002` uses)
  — same name, description, price, stock as the client-side fallback, so the
  two stay in lockstep by construction.
- Give each a real `image_url` this time (currently blank in
  `FALLBACK_PRODUCTS`), using the same `https://picsum.photos/seed/<slug>/400/400`
  placeholder-photo convention `002-seed-products.sql` already established
  (e.g. `seed/ecuador72bar`, `seed/seasaltcaramel`, ...) — consistent with the
  rest of the catalog, no new external dependency to vet, and easy to swap for
  real product photography later since it's just a column value.
- Idempotency guard: precondition per product row on
  `SELECT COUNT(*) FROM product WHERE name = '<exact name>'` (not a single
  category-wide count like `002` uses for its first insert — `Food & Sweets`
  already has 5 rows, so a category-wide "any rows exist" guard would
  incorrectly skip the whole insert).
- Existing 5 generic products are left untouched — `ui-shop`'s catalog is
  unaffected other than `Food & Sweets` now listing 13 items instead of 5,
  which is a reasonable general-store outcome, not a regression.

### 2. Make ui-demo show only its own 8, not all 13 — `ui-demo/src/store.tsx`

Once §1 ships, the live `Food & Sweets` category has 13 products: the 8
curated + 5 generic. Showing all 13 on `sichocolate.com` would mix "Dark
Chocolate Bar" ($6.99, generic) in with "Ecuador 72% Single-Origin Bar"
($12.50, on-brand) — inconsistent voice, and also means the grid would grow to
13 items instead of the designed 8.

- Export the list of curated names from `ui-demo/src/api.ts` (the
  `FALLBACK_PRODUCTS.map(p => p.name)` values) as the single source of truth
  for "which live products belong to sichocolate.com."
- In `store.tsx`'s `refresh()`, after filtering to the chocolate category,
  further filter to products whose `name` is in that curated-names set. This
  keeps `FALLBACK_PRODUCTS` itself as the one place that defines "what
  sichocolate.com sells" — the DB rows just need matching names, no new
  schema column (e.g. a `brand`/`storefront` flag) needed for 8 hardcoded
  items.
- Once all 8 names resolve to real rows, `chocolate.length === 8`, so
  `topUp = FALLBACK_PRODUCTS.slice(0, max(0, 8 - 8)) = []` — the fallback
  catalog stops engaging at all, and every item in the grid has a real,
  positive id. No change needed to `CheckoutPage.tsx`'s
  `l.product.id > 0` filter — it already passes for all 8 once they're real.

### 3. No change needed for "keep the current pictures while we fetch the real ones"

Checked: `Product.imageUrl` is **not rendered anywhere** in `ui-demo` today —
every product visual (`Bestsellers`, `Signature`, `ProductPage`, `CartDrawer`,
`CheckoutPage`, ...) uses the generated `ChocolateArt` SVG illustration
(`ui-demo/src/components/ChocolateArt.tsx`), which derives its look
deterministically from `product.name`/`product.id` alone, never from
`imageUrl`.

Because §2 matches live rows to fallback pieces **by the same name**,
`ChocolateArt` renders an identical illustration whether a given piece is
currently a `-1`..`-8` fallback or its real, positive-id DB replacement — same
instant paint, zero image network request, no loading state to design for.
"The speed we have at the moment" is preserved automatically, not as a
separate task.

(Real product photography, and wiring `imageUrl` into the UI to replace
`ChocolateArt`, is a legitimate future step — explicitly out of scope here so
this stays a data + one-filter change, not a redesign.)

### 4. Deploy

- `shop` runs its own Liquibase migration on startup (same as every other
  service in this repo) — no separate migration-runner step.
- Rebuild/push `ui-demo` (new `store.tsx` filter logic), same as
  `sichocolate.md`'s image build/push steps
  (`docker buildx build --platform linux/amd64 ... --push`).
- Restart `shop` and `ui-demo`:
  ```
  kubectl -n granite rollout restart deployment shop ui-demo
  kubectl -n granite rollout status deployment shop --timeout=120s
  kubectl -n granite rollout status deployment ui-demo --timeout=120s
  ```
- No `config-patch`/`production-patches`/secret changes needed — this is a
  data seed + one frontend filter, not a config change.

### 5. Verify

```
curl -s "https://sichocolate.com/api/shop/products?size=50" | jq '.items[] | select(.categoryId==4) | {id, name}'
```
— should list all 13 `Food & Sweets` rows (5 generic + 8 curated), all with
positive ids.

On `sichocolate.com`: add one of the 8 curated pieces (e.g. "Ecuador 72%
Single-Origin Bar") to cart, go to checkout — the "editorial preview catalog"
warning should no longer appear, and placing the order should succeed.

Confirm `ui-shop` (when `app/` is the live overlay) still lists `Food & Sweets`
correctly with all 13 products — not a regression, just a bigger category.

---

## Follow-up (2026-08-09): §2's name filter had to go

§2's curated-name allowlist worked for the 8 seeded pieces and broke the moment
anyone used ui-demo's own back of house. Creating a product there POSTed fine,
showed "Added … to the collection", and then vanished: `refresh()` re-applied
`CURATED_PRODUCT_NAMES`, and since the admin list is built from the same
filtered store (`AdminPage.tsx`), the new piece was not merely absent from the
boutique — it could not be edited or deleted either. Four such rows had
accumulated in the live DB (ids 23–26).

The premise of §2 — "no new schema column needed for 8 hardcoded items" — only
holds while the set is closed. It isn't: the storefront has an admin UI whose
entire purpose is to open it.

### What replaced it

`011-sichocolate-category.sql` gives the boutique its own `SI Chocolate`
category and moves everything out of `Food & Sweets` except the 5 generic
products from `002`. Membership is now a property of the row, so anything an
admin creates in the SI Chocolate category shows up, and the constraint from
"Root cause" above still holds — ui-shop's `Food & Sweets` keeps exactly the 5
products it started with.

`ui-demo/src/store.tsx` resolves the category by exact name
(`SICHOCOLATE_CATEGORY_NAME` in `api.ts`, replacing `CURATED_PRODUCT_NAMES`) and
takes everything in it. **The category name is load-bearing**: rename it in the
DB and the storefront silently falls back to the editorial catalog. The
`FALLBACK_PRODUCTS` top-up and §3's `ChocolateArt` reasoning are unchanged.

`AdminPage.tsx` now offers only the SI Chocolate category when creating a
piece — the shared shop's other categories are exactly the way to make a
product invisible again.

### Verify

```
curl -s "https://sichocolate.com/api/shop/categories?size=50" | jq '.items[] | select(.name=="SI Chocolate")'
curl -s "https://sichocolate.com/api/shop/products?size=50" | jq --argjson c "$SI_ID" '[.items[] | select(.categoryId==$c)] | length'
```
Then in ui-demo's back of house: add a piece and confirm it appears in the
grid without a rebuild. `Food & Sweets` should be back down to 5 products.
