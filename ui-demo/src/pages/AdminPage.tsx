import { Fragment, useEffect, useState, type ChangeEvent, type FormEvent } from 'react';
import { Link } from 'react-router';
import { api } from '../api';
import { useAuth } from '../auth';
import { formatPrice, useShop } from '../store';
import type { CreateProductRequest, MediaItem, OrderResponse, Product } from '../types';
import { ChocolateArt, variantFor } from '../components/ChocolateArt';
import { getDefaultMedia } from '../utils/media';

const EMPTY_FORM: CreateProductRequest = {
  name: '',
  description: '',
  price: 0,
  stock: 0,
  categoryId: 0,
  imageUrl: '',
  media: [],
};

const inputStyle =
  'w-full border border-cocoa/20 bg-white/70 px-4 py-3 text-sm text-cocoa placeholder:text-cocoa/40 focus:border-gold focus:outline-none';

export function AdminPage() {
  const { user, isAdmin, loading } = useAuth();
  const { products, categories, chocolateCategoryId, refresh, live } = useShop();
  const [form, setForm] = useState<CreateProductRequest>(EMPTY_FORM);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [message, setMessage] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null);

  if (loading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-ivory pt-28 text-cocoa/50">
        Loading…
      </div>
    );
  }

  if (!isAdmin) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center gap-4 bg-ivory px-6 pt-28 text-center">
        <h1 className="font-display text-[32px] text-cocoa">The Back of House is Locked</h1>
        <p className="max-w-md text-cocoa/60">
          This area is reserved for SI Chocolate staff. Sign in with an admin account to manage the
          collection.
        </p>
        <Link
          to="/"
          className="mt-4 border border-cocoa px-8 py-3 text-xs uppercase tracking-[0.18em] text-cocoa transition-colors duration-300 hover:bg-cocoa hover:text-ivory"
        >
          Back to the Boutique
        </Link>
      </div>
    );
  }

  // Only live backend products can be managed (fallback pieces have negative ids).
  const managed = products.filter((p) => p.id > 0);

  const startEdit = (p: Product) => {
    setEditingId(p.id);
    setForm({
      name: p.name,
      description: p.description,
      price: p.price,
      stock: p.stock,
      categoryId: p.categoryId,
      imageUrl: p.imageUrl ?? '',
      media: p.media ?? [],
    });
    setMessage(null);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };

  const reset = () => {
    setEditingId(null);
    setForm(EMPTY_FORM);
  };

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setMessage(null);
    const body: CreateProductRequest = {
      ...form,
      categoryId: form.categoryId || chocolateCategoryId || categories[0]?.id || 0,
    };
    try {
      if (editingId !== null) {
        await api.updateProduct(editingId, body);
        setMessage({ kind: 'ok', text: `Updated “${body.name}”.` });
      } else {
        await api.createProduct(body);
        setMessage({ kind: 'ok', text: `Added “${body.name}” to the collection.` });
      }
      reset();
      await refresh();
    } catch (err) {
      setMessage({ kind: 'error', text: err instanceof Error ? err.message : String(err) });
    } finally {
      setBusy(false);
    }
  };

  const onDelete = async (p: Product) => {
    if (!window.confirm(`Remove “${p.name}” from the collection?`)) return;
    setBusy(true);
    setMessage(null);
    try {
      await api.deleteProduct(p.id);
      if (editingId === p.id) reset();
      setMessage({ kind: 'ok', text: `Removed “${p.name}”.` });
      await refresh();
    } catch (err) {
      setMessage({ kind: 'error', text: err instanceof Error ? err.message : String(err) });
    } finally {
      setBusy(false);
    }
  };

  // Upload/remove touch the storage backend, so they persist immediately
  // (unlike the rest of the form's fields, which wait for Save Changes).
  const persistMedia = async (media: MediaItem[]) => {
    const next = { ...form, media };
    setForm(next);
    if (editingId !== null) {
      await api.updateProduct(editingId, next);
      await refresh();
    }
  };

  const onFileSelected = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    setUploading(true);
    setMessage(null);
    try {
      const item = await api.uploadProductImage(file);
      await persistMedia([...(form.media ?? []), item]);
    } catch (err) {
      setMessage({ kind: 'error', text: err instanceof Error ? err.message : String(err) });
    } finally {
      setUploading(false);
    }
  };

  const onRemoveMedia = async (item: MediaItem) => {
    setMessage(null);
    try {
      await api.deleteStorageObject(item.key);
      // Filtering the item out already clears its isDefault flag with it —
      // no other image gets silently promoted (see getDefaultMedia).
      await persistMedia((form.media ?? []).filter((m) => m.key !== item.key));
    } catch (err) {
      setMessage({ kind: 'error', text: err instanceof Error ? err.message : String(err) });
    }
  };

  // Local-only until Save Changes — no storage side effect, unlike upload/remove.
  const onSetDefaultMedia = (item: MediaItem) => {
    setForm({
      ...form,
      media: (form.media ?? []).map((m) => ({ ...m, isDefault: m.key === item.key })),
    });
  };

  return (
    <div className="bg-ivory pt-28 lg:pt-32">
      <div className="mx-auto max-w-7xl px-5 pb-24 lg:px-8">
        <p className="text-xs uppercase tracking-[0.3em] text-terracotta">Back of House</p>
        <h1 className="mt-3 font-display text-[36px] leading-tight text-cocoa lg:text-[48px]">
          Manage the Collection
        </h1>
        <p className="mt-2 text-sm text-cocoa/60">
          Signed in as <span className="text-cocoa">{user?.name}</span> with admin access.
          {!live && ' The shop backend is unreachable — changes cannot be saved right now.'}
        </p>
        <p className="mt-2 text-sm">
          <Link
            to="/admin/users"
            className="text-xs uppercase tracking-[0.14em] text-cocoa underline decoration-gold underline-offset-4 hover:text-terracotta"
          >
            Manage customer accounts →
          </Link>
        </p>

        {message && (
          <p
            role="status"
            className={`mt-6 border-l-2 px-4 py-3 text-sm ${
              message.kind === 'ok'
                ? 'border-sage bg-sage/10 text-cocoa'
                : 'border-terracotta bg-terracotta/10 text-terracotta'
            }`}
          >
            {message.text}
          </p>
        )}

        <div className="mt-10 grid gap-12 lg:grid-cols-[2fr_3fr]">
          {/* create / edit form */}
          <section aria-label={editingId !== null ? 'Edit product' : 'Add product'}>
            <h2 className="font-display text-[24px] text-cocoa">
              {editingId !== null ? 'Edit Piece' : 'Add a New Piece'}
            </h2>
            <form onSubmit={onSubmit} className="mt-6 space-y-4">
              <div>
                <label htmlFor="admin-name" className="mb-1 block text-xs uppercase tracking-[0.16em] text-cocoa/60">
                  Name
                </label>
                <input
                  id="admin-name"
                  required
                  value={form.name}
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                  className={inputStyle}
                  placeholder="Ecuador 72% Single-Origin Bar"
                />
              </div>
              <div>
                <label htmlFor="admin-desc" className="mb-1 block text-xs uppercase tracking-[0.16em] text-cocoa/60">
                  Description
                </label>
                <textarea
                  id="admin-desc"
                  required
                  rows={3}
                  value={form.description}
                  onChange={(e) => setForm({ ...form, description: e.target.value })}
                  className={inputStyle}
                  placeholder="Tasting notes, origin, finish…"
                />
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label htmlFor="admin-price" className="mb-1 block text-xs uppercase tracking-[0.16em] text-cocoa/60">
                    Price (USD)
                  </label>
                  <input
                    id="admin-price"
                    required
                    type="number"
                    min="0"
                    step="0.01"
                    value={form.price}
                    onChange={(e) => setForm({ ...form, price: Number(e.target.value) })}
                    className={inputStyle}
                  />
                </div>
                <div>
                  <label htmlFor="admin-stock" className="mb-1 block text-xs uppercase tracking-[0.16em] text-cocoa/60">
                    Stock
                  </label>
                  <input
                    id="admin-stock"
                    required
                    type="number"
                    min="0"
                    step="1"
                    value={form.stock}
                    onChange={(e) => setForm({ ...form, stock: Number(e.target.value) })}
                    className={inputStyle}
                  />
                </div>
              </div>
              <div>
                <label htmlFor="admin-category" className="mb-1 block text-xs uppercase tracking-[0.16em] text-cocoa/60">
                  Category
                </label>
                <select
                  id="admin-category"
                  value={form.categoryId || chocolateCategoryId || ''}
                  onChange={(e) => setForm({ ...form, categoryId: Number(e.target.value) })}
                  className={inputStyle}
                >
                  {categories.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name}
                    </option>
                  ))}
                </select>
              </div>

              {editingId !== null && (
                <div className="border-t border-cocoa/10 pt-4">
                  <p className="mb-1 block text-xs uppercase tracking-[0.16em] text-cocoa/60">Media</p>
                  <p className="text-xs text-cocoa/50">
                    The default image is used as this piece's photo across the boutique — click
                    "Save Changes" below to persist your selection.
                  </p>

                  <div className="mt-3 flex flex-wrap gap-3">
                    {(form.media ?? []).map((item) => {
                      const isDefault = getDefaultMedia(form.media)?.key === item.key;
                      return (
                        <div key={item.key} className="w-24">
                          <div
                            className={`relative flex h-24 w-24 items-center justify-center overflow-hidden rounded-md bg-cocoa/5 ${
                              isDefault ? 'ring-2 ring-gold' : ''
                            }`}
                          >
                            {isDefault && (
                              <span className="absolute left-1 top-1 bg-gold px-1.5 py-0.5 text-[9px] uppercase tracking-wide text-cocoa">
                                Default
                              </span>
                            )}
                            <img src={item.url} alt="" className="h-full w-full object-contain" />
                          </div>
                          <button
                            type="button"
                            disabled={isDefault}
                            onClick={() => onSetDefaultMedia(item)}
                            className="mt-1 w-full text-[10px] uppercase tracking-wide text-cocoa/70 underline decoration-gold underline-offset-2 hover:text-terracotta disabled:opacity-40"
                          >
                            {isDefault ? 'Default' : 'Set default'}
                          </button>
                          <button
                            type="button"
                            onClick={() => onRemoveMedia(item)}
                            className="mt-1 w-full text-[10px] uppercase tracking-wide text-terracotta/80 underline underline-offset-2 hover:text-terracotta"
                          >
                            Remove
                          </button>
                        </div>
                      );
                    })}
                  </div>

                  <div className="mt-3">
                    <input
                      type="file"
                      accept="image/jpeg,image/png,image/webp"
                      disabled={uploading}
                      onChange={onFileSelected}
                      className="text-xs text-cocoa/70"
                    />
                    {uploading && <span className="ml-2 text-xs text-cocoa/50">Uploading…</span>}
                  </div>
                </div>
              )}

              <div className="flex gap-3 pt-2">
                <button
                  type="submit"
                  disabled={busy || !live}
                  className="bg-cocoa px-8 py-3.5 text-xs uppercase tracking-[0.18em] text-ivory transition-colors duration-300 hover:bg-espresso disabled:cursor-not-allowed disabled:opacity-40"
                >
                  {busy ? 'Saving…' : editingId !== null ? 'Save Changes' : 'Add to Collection'}
                </button>
                {editingId !== null && (
                  <button
                    type="button"
                    onClick={reset}
                    className="border border-cocoa/30 px-6 py-3.5 text-xs uppercase tracking-[0.18em] text-cocoa transition-colors hover:border-cocoa"
                  >
                    Cancel
                  </button>
                )}
              </div>
            </form>
          </section>

          {/* product list */}
          <section aria-label="Products">
            <h2 className="font-display text-[24px] text-cocoa">
              Current Collection <span className="text-cocoa/40">({managed.length})</span>
            </h2>
            <ul className="mt-6 divide-y divide-cocoa/10 border-y border-cocoa/10">
              {managed.map((p) => {
                const defaultImage = getDefaultMedia(p.media);
                return (
                <li key={p.id} className="flex items-center gap-4 py-4">
                  <div className="h-16 w-16 shrink-0 overflow-hidden rounded-md">
                    {defaultImage
                      ? <img src={defaultImage.url} alt={p.name} className="h-full w-full object-cover" />
                      : <ChocolateArt seed={p.id} variant={variantFor(p.name, p.id)} className="h-full w-full" />}
                  </div>
                  <div className="min-w-0 flex-1">
                    <Link to={`/products/${p.id}`} className="font-display text-lg text-cocoa hover:text-terracotta">
                      {p.name}
                    </Link>
                    <p className="truncate text-sm text-cocoa/50">
                      {formatPrice(p.price)} · {p.stock} in stock
                    </p>
                  </div>
                  <button
                    onClick={() => startEdit(p)}
                    className="text-xs uppercase tracking-[0.14em] text-cocoa underline decoration-gold underline-offset-4 hover:text-terracotta"
                  >
                    Edit
                  </button>
                  <button
                    onClick={() => onDelete(p)}
                    disabled={busy}
                    className="text-xs uppercase tracking-[0.14em] text-terracotta/80 underline underline-offset-4 hover:text-terracotta disabled:opacity-40"
                  >
                    Delete
                  </button>
                </li>
                );
              })}
              {managed.length === 0 && (
                <li className="py-6 text-sm text-cocoa/50">No live products — is the shop backend running?</li>
              )}
            </ul>
          </section>
        </div>

        <AllOrders />
      </div>
    </div>
  );
}

const STATUS_STYLES: Record<string, string> = {
  PENDING: 'bg-gold/15 text-cocoa',
  PAID: 'bg-sage/20 text-cocoa',
  SHIPPED: 'bg-sage/20 text-cocoa',
  DELIVERED: 'bg-sage/30 text-cocoa',
  CANCELLED: 'bg-terracotta/15 text-terracotta',
};

/** Every order placed by every customer — the endpoint is ROLE_ADMIN only. */
function AllOrders() {
  const [orders, setOrders] = useState<OrderResponse[]>([]);
  const [total, setTotal] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [expanded, setExpanded] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .getAllOrders()
      .then((page) => {
        if (cancelled) return;
        setOrders(page.items);
        setTotal(page.total);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : String(err));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <section aria-label="All orders" className="mt-16">
      <h2 className="font-display text-[24px] text-cocoa">
        Customer Orders {total > 0 && <span className="text-cocoa/40">({total})</span>}
      </h2>
      <p className="mt-1 text-sm text-cocoa/60">Every order placed in the boutique, newest first.</p>

      {loading && <p className="mt-6 text-sm text-cocoa/50">Loading orders…</p>}
      {error && (
        <p className="mt-6 border-l-2 border-terracotta bg-terracotta/10 px-4 py-3 text-sm text-terracotta">
          Could not load orders: {error}
          {error.includes('404') && ' — restart the shop service to pick up the new /orders/all endpoint.'}
        </p>
      )}
      {!loading && !error && orders.length === 0 && (
        <p className="mt-6 text-sm text-cocoa/50">No orders yet — the boutique awaits its first customer.</p>
      )}

      {orders.length > 0 && (
        <div className="mt-6 overflow-x-auto">
          <table className="w-full min-w-[640px] text-left text-sm">
            <thead>
              <tr className="border-b border-cocoa/15 text-xs uppercase tracking-[0.16em] text-cocoa/50">
                <th className="py-3 pr-4 font-normal">Order</th>
                <th className="py-3 pr-4 font-normal">Customer</th>
                <th className="py-3 pr-4 font-normal">Placed</th>
                <th className="py-3 pr-4 font-normal">Status</th>
                <th className="py-3 pr-4 text-right font-normal">Total</th>
                <th className="py-3 font-normal" />
              </tr>
            </thead>
            <tbody className="divide-y divide-cocoa/10">
              {orders.map((order) => (
                <Fragment key={order.id}>
                  <tr className="text-cocoa">
                    <td className="py-4 pr-4 font-display text-base">#{order.id}</td>
                    <td className="py-4 pr-4">{order.username}</td>
                    <td className="py-4 pr-4 text-cocoa/60">
                      {new Date(order.createdAt).toLocaleString(undefined, {
                        dateStyle: 'medium',
                        timeStyle: 'short',
                      })}
                    </td>
                    <td className="py-4 pr-4">
                      <span
                        className={`inline-block rounded-full px-3 py-1 text-[11px] uppercase tracking-[0.12em] ${
                          STATUS_STYLES[order.status] ?? 'bg-cocoa/10 text-cocoa'
                        }`}
                      >
                        {order.status}
                      </span>
                    </td>
                    <td className="py-4 pr-4 text-right">{formatPrice(order.total)}</td>
                    <td className="py-4 text-right">
                      <button
                        onClick={() => setExpanded(expanded === order.id ? null : order.id)}
                        aria-expanded={expanded === order.id}
                        className="text-xs uppercase tracking-[0.14em] text-cocoa underline decoration-gold underline-offset-4 hover:text-terracotta"
                      >
                        {expanded === order.id ? 'Hide' : 'Items'}
                      </button>
                    </td>
                  </tr>
                  {expanded === order.id && (
                    <tr>
                      <td colSpan={6} className="bg-cocoa/[0.03] px-4 py-4">
                        <ul className="space-y-2">
                          {order.items.map((item) => (
                            <li key={item.id} className="flex justify-between gap-4 text-cocoa/80">
                              <span>
                                {item.quantity} × {item.productName}
                              </span>
                              <span>{formatPrice(item.unitPrice * item.quantity)}</span>
                            </li>
                          ))}
                        </ul>
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
