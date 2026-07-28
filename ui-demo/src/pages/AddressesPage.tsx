import { useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { api } from '../api';
import type { AddressRequest, AddressResponse } from '../types';

const inputStyle =
  'w-full border border-cocoa/20 bg-white/70 px-4 py-3 text-sm text-cocoa placeholder:text-cocoa/40 focus:border-gold focus:outline-none';

const EMPTY_ADDRESS: AddressRequest = {
  label: '',
  recipientName: '',
  addressLine1: '',
  addressLine2: '',
  city: '',
  state: '',
  zipCode: '',
  country: '',
  isDefault: false,
};

export function AddressesPage() {
  const [addresses, setAddresses] = useState<AddressResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [form, setForm] = useState<AddressRequest>(EMPTY_ADDRESS);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null);

  const load = () => api.getAddresses().then(setAddresses);

  useEffect(() => {
    load()
      .catch((err) => setMessage({ kind: 'error', text: err instanceof Error ? err.message : String(err) }))
      .finally(() => setLoading(false));
  }, []);

  const startEdit = (a: AddressResponse) => {
    setEditingId(a.id);
    setForm({
      label: a.label ?? '',
      recipientName: a.recipientName,
      addressLine1: a.addressLine1,
      addressLine2: a.addressLine2 ?? '',
      city: a.city,
      state: a.state ?? '',
      zipCode: a.zipCode,
      country: a.country,
      isDefault: a.isDefault,
    });
    setMessage(null);
  };

  const reset = () => {
    setEditingId(null);
    setForm(EMPTY_ADDRESS);
  };

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!form.recipientName || !form.addressLine1 || !form.city || !form.zipCode || !form.country) {
      setMessage({ kind: 'error', text: 'Please complete the required address fields.' });
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      if (editingId !== null) {
        await api.updateAddress(editingId, form);
        setMessage({ kind: 'ok', text: 'Address updated.' });
      } else {
        await api.createAddress(form);
        setMessage({ kind: 'ok', text: 'Address added.' });
      }
      reset();
      await load();
    } catch (err) {
      setMessage({ kind: 'error', text: err instanceof Error ? err.message : String(err) });
    } finally {
      setBusy(false);
    }
  };

  const onDelete = async (a: AddressResponse) => {
    if (!window.confirm(`Remove the address “${a.label || a.recipientName}”?`)) return;
    setBusy(true);
    setMessage(null);
    try {
      await api.deleteAddress(a.id);
      if (editingId === a.id) reset();
      await load();
    } catch (err) {
      setMessage({ kind: 'error', text: err instanceof Error ? err.message : String(err) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <p className="text-xs uppercase tracking-[0.3em] text-terracotta">Your Account</p>
      <h1 className="mt-3 font-display text-[36px] leading-tight text-cocoa lg:text-[48px]">Addresses</h1>

      <section aria-label="Address book" className="mt-10">
          <h2 className="font-display text-[24px] text-cocoa">
            {editingId !== null ? 'Edit Address' : 'Add an Address'}
          </h2>
          {message && (
            <p
              role="status"
              className={`mt-4 border-l-2 px-4 py-3 text-sm ${
                message.kind === 'ok'
                  ? 'border-sage bg-sage/10 text-cocoa'
                  : 'border-terracotta bg-terracotta/10 text-terracotta'
              }`}
            >
              {message.text}
            </p>
          )}
          <form onSubmit={onSubmit} className="mt-6 grid grid-cols-1 gap-3 sm:grid-cols-2">
            <input
              aria-label="Label"
              placeholder="Label (e.g. Home, Work)"
              value={form.label ?? ''}
              onChange={(e) => setForm({ ...form, label: e.target.value })}
              className={`${inputStyle} sm:col-span-2`}
            />
            <input
              aria-label="Recipient name"
              placeholder="Recipient name *"
              value={form.recipientName}
              onChange={(e) => setForm({ ...form, recipientName: e.target.value })}
              className={`${inputStyle} sm:col-span-2`}
            />
            <input
              aria-label="Address line 1"
              placeholder="Address line 1 *"
              value={form.addressLine1}
              onChange={(e) => setForm({ ...form, addressLine1: e.target.value })}
              className={`${inputStyle} sm:col-span-2`}
            />
            <input
              aria-label="Address line 2"
              placeholder="Address line 2"
              value={form.addressLine2 ?? ''}
              onChange={(e) => setForm({ ...form, addressLine2: e.target.value })}
              className={`${inputStyle} sm:col-span-2`}
            />
            <input
              aria-label="City"
              placeholder="City *"
              value={form.city}
              onChange={(e) => setForm({ ...form, city: e.target.value })}
              className={inputStyle}
            />
            <input
              aria-label="State"
              placeholder="State"
              value={form.state ?? ''}
              onChange={(e) => setForm({ ...form, state: e.target.value })}
              className={inputStyle}
            />
            <input
              aria-label="ZIP code"
              placeholder="ZIP code *"
              value={form.zipCode}
              onChange={(e) => setForm({ ...form, zipCode: e.target.value })}
              className={inputStyle}
            />
            <input
              aria-label="Country"
              placeholder="Country *"
              value={form.country}
              onChange={(e) => setForm({ ...form, country: e.target.value })}
              className={inputStyle}
            />
            <label className="flex items-center gap-2 text-sm text-cocoa sm:col-span-2">
              <input
                type="checkbox"
                checked={form.isDefault ?? false}
                onChange={(e) => setForm({ ...form, isDefault: e.target.checked })}
                className="accent-[#C7A56B]"
              />
              Use as default delivery address
            </label>
            <div className="flex gap-3 pt-2 sm:col-span-2">
              <button
                type="submit"
                disabled={busy}
                className="bg-cocoa px-8 py-3.5 text-xs uppercase tracking-[0.18em] text-ivory transition-colors duration-300 hover:bg-espresso disabled:cursor-not-allowed disabled:opacity-40"
              >
                {busy ? 'Saving…' : editingId !== null ? 'Save Changes' : 'Add Address'}
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

          <h3 className="mt-12 font-display text-[20px] text-cocoa">
            Saved Addresses {addresses.length > 0 && <span className="text-cocoa/40">({addresses.length})</span>}
          </h3>
          {loading ? (
            <p className="mt-4 text-sm text-cocoa/50">Loading…</p>
          ) : addresses.length === 0 ? (
            <p className="mt-4 text-sm text-cocoa/50">
              No saved addresses yet — add one above and it'll be ready at checkout.
            </p>
          ) : (
            <ul className="mt-4 divide-y divide-cocoa/10 border-y border-cocoa/10">
              {addresses.map((a) => (
                <li key={a.id} className="flex items-start justify-between gap-4 py-4">
                  <div className="min-w-0">
                    <p className="text-cocoa">
                      <span className="font-medium">{a.recipientName}</span>
                      {a.label && <span className="text-cocoa/50"> ({a.label})</span>}
                      {a.isDefault && (
                        <span className="ml-2 inline-block rounded-full bg-gold/15 px-2 py-0.5 text-[10px] uppercase tracking-[0.12em] text-cocoa">
                          Default
                        </span>
                      )}
                    </p>
                    <p className="mt-1 text-sm text-cocoa/60">
                      {a.addressLine1}
                      {a.addressLine2 ? `, ${a.addressLine2}` : ''}, {a.city}
                      {a.state ? `, ${a.state}` : ''} {a.zipCode}, {a.country}
                    </p>
                  </div>
                  <div className="flex shrink-0 gap-3">
                    <button
                      onClick={() => startEdit(a)}
                      className="text-xs uppercase tracking-[0.14em] text-cocoa underline decoration-gold underline-offset-4 hover:text-terracotta"
                    >
                      Edit
                    </button>
                    <button
                      onClick={() => onDelete(a)}
                      disabled={busy}
                      className="text-xs uppercase tracking-[0.14em] text-terracotta/80 underline underline-offset-4 hover:text-terracotta disabled:opacity-40"
                    >
                      Delete
                    </button>
                  </div>
                </li>
              ))}
            </ul>
          )}

          <Link
            to="/"
            className="mt-10 inline-block border border-cocoa px-8 py-3 text-xs uppercase tracking-[0.18em] text-cocoa transition-colors duration-300 hover:bg-cocoa hover:text-ivory"
          >
            Back to the Boutique
          </Link>
        </section>
    </div>
  );
}
