import { useEffect, useState, type FormEvent } from 'react';
import { api } from '../api';
import type { ProfileResponse } from '../types';

const inputStyle =
  'w-full border border-cocoa/20 bg-white/70 px-4 py-3 text-sm text-cocoa placeholder:text-cocoa/40 focus:border-gold focus:outline-none';

export function ProfilePage() {
  return (
    <div>
      <p className="text-xs uppercase tracking-[0.3em] text-terracotta">Your Account</p>
      <h1 className="mt-3 font-display text-[36px] leading-tight text-cocoa lg:text-[48px]">My Profile</h1>

      <div className="mt-10">
        <ProfileDetails />
      </div>
    </div>
  );
}

function ProfileDetails() {
  const [profile, setProfile] = useState<ProfileResponse | null>(null);
  const [form, setForm] = useState({ email: '', firstName: '', lastName: '', displayName: '' });
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
        setForm({
          email: p.email ?? '',
          firstName: p.firstName ?? '',
          lastName: p.lastName ?? '',
          displayName: p.displayName ?? '',
        });
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
              <label
                htmlFor="profile-display-name"
                className="mb-1 block text-xs uppercase tracking-[0.16em] text-cocoa/60"
              >
                Display name
              </label>
              <input
                id="profile-display-name"
                value={form.displayName}
                onChange={(e) => setForm({ ...form, displayName: e.target.value })}
                placeholder="How your name appears across the site"
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
