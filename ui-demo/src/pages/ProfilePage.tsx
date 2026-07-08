import { useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { api } from '../api';
import { useAuth } from '../auth';
import type { AddressRequest, AddressResponse, ProfileResponse } from '../types';

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

export function ProfilePage() {
  const { isAuthenticated, loading: authLoading, login } = useAuth();

  if (authLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-ivory pt-28 text-cocoa/50">
        Loading…
      </div>
    );
  }

  if (!isAuthenticated) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center gap-4 bg-ivory px-6 pt-28 text-center">
        <h1 className="font-display text-[32px] text-cocoa">Your Account Awaits</h1>
        <p className="max-w-md text-cocoa/60">Sign in to view and manage your profile and addresses.</p>
        <button
          onClick={login}
          className="mt-4 bg-cocoa px-8 py-3 text-xs uppercase tracking-[0.18em] text-ivory transition-colors duration-300 hover:bg-espresso"
        >
          Sign In
        </button>
      </div>
    );
  }

  return (
    <div className="bg-ivory pt-28 lg:pt-32">
      <div className="mx-auto max-w-3xl px-5 pb-24 lg:px-8">
        <p className="text-xs uppercase tracking-[0.3em] text-terracotta">Your Account</p>
        <h1 className="mt-3 font-display text-[36px] leading-tight text-cocoa lg:text-[48px]">My Profile</h1>

        <div className="mt-10 space-y-14">
          <ProfileDetails />
          <AddressBook />
        </div>
      </div>
    </div>
  );
}

function ProfileDetails() {
  const [profile, setProfile] = useState<ProfileResponse | null>(null);
  const [form, setForm] = useState({ email: '', firstName: '', lastName: '' });
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .getMyProfile()
      .then((p) => {
        if (cancelled) return;
        setProfile(p);
        setForm({ email: p.email ?? '', firstName: p.firstName ?? '', lastName: p.lastName ?? '' });
      })
      .catch((err) => {
        if (!cancelled) setMessage({ kind: 'error', text: err instanceof Error ? err.message : String(err) });
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setMessage(null);
    try {
      const updated = await api.updateMyProfile(form);
      setProfile(updated);
      setMessage({ kind: 'ok', text: 'Profile updated.' });
    } catch (err) {
      setMessage({ kind: 'error', text: err instanceof Error ? err.message : String(err) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-label="Profile details">
      <h2 className="font-display text-[24px] text-cocoa">Personal Details</h2>
      {loading ? (
        <p className="mt-4 text-sm text-cocoa/50">Loading…</p>
      ) : (
        <>
          {profile && <p className="mt-1 text-sm text-cocoa/50">Signed in as {profile.username}</p>}
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
          <form onSubmit={onSubmit} className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <label htmlFor="profile-first" className="mb-1 block text-xs uppercase tracking-[0.16em] text-cocoa/60">
                First name
              </label>
              <input
                id="profile-first"
                value={form.firstName}
                onChange={(e) => setForm({ ...form, firstName: e.target.value })}
                className={inputStyle}
              />
            </div>
            <div>
              <label htmlFor="profile-last" className="mb-1 block text-xs uppercase tracking-[0.16em] text-cocoa/60">
                Last name
              </label>
              <input
                id="profile-last"
                value={form.lastName}
                onChange={(e) => setForm({ ...form, lastName: e.target.value })}
                className={inputStyle}
              />
            </div>
            <div className="sm:col-span-2">
              <label htmlFor="profile-email" className="mb-1 block text-xs uppercase tracking-[0.16em] text-cocoa/60">
                Email
              </label>
              <input
                id="profile-email"
                type="email"
                value={form.email}
                onChange={(e) => setForm({ ...form, email: e.target.value })}
                className={inputStyle}
              />
            </div>
            <div className="sm:col-span-2">
              <button
                type="submit"
                disabled={busy}
                className="bg-cocoa px-8 py-3.5 text-xs uppercase tracking-[0.18em] text-ivory transition-colors duration-300 hover:bg-espresso disabled:cursor-not-allowed disabled:opacity-40"
              >
                {busy ? 'Saving…' : 'Save Changes'}
              </button>
            </div>
          </form>
        </>
      )}
    </section>
  );
}

function AddressBook() {
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
    <section aria-label="Address book">
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
  );
}
