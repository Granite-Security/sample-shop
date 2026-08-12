import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { api } from '../api';
import { Avatar } from '../components/Avatar';
import type { HandleAvailability, ProfileResponse } from '../types';

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

function formOf(p: ProfileResponse) {
  return {
    email: p.email ?? '',
    firstName: p.firstName ?? '',
    lastName: p.lastName ?? '',
    displayName: p.displayName ?? '',
    bio: p.bio ?? '',
  };
}

// Read-only details with an explicit Edit toggle, mirroring ui-shop's
// Profile page — the form only appears when the customer asks for it.
function ProfileDetails() {
  const [profile, setProfile] = useState<ProfileResponse | null>(null);
  const [form, setForm] = useState({ email: '', firstName: '', lastName: '', displayName: '', bio: '' });
  const [editing, setEditing] = useState(false);
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
        setForm(formOf(p));
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

  const startEditing = () => {
    if (profile) setForm(formOf(profile));
    setMessage(null);
    setEditing(true);
  };

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setMessage(null);
    try {
      const updated = await api.updateMyProfile(form);
      setProfile(updated);
      setEditing(false);
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

          {profile && <AvatarPicker profile={profile} onChange={setProfile} />}

          {!profile ? (
            <p className="mt-4 text-sm text-cocoa/50">Could not load your profile.</p>
          ) : editing ? (
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
                <label htmlFor="profile-bio" className="mb-1 block text-xs uppercase tracking-[0.16em] text-cocoa/60">
                  Bio
                </label>
                <textarea
                  id="profile-bio"
                  rows={4}
                  value={form.bio}
                  onChange={(e) => setForm({ ...form, bio: e.target.value.slice(0, 500) })}
                  placeholder="Shown on your public profile"
                  className={inputStyle}
                />
                <p className="mt-1 text-right text-[11px] text-cocoa/50">{form.bio.length}/500</p>
              </div>
              <div className="flex gap-3 sm:col-span-2">
                <button
                  type="submit"
                  disabled={busy}
                  className="bg-cocoa px-8 py-3.5 text-xs uppercase tracking-[0.18em] text-ivory transition-colors duration-300 hover:bg-espresso disabled:cursor-not-allowed disabled:opacity-40"
                >
                  {busy ? 'Saving…' : 'Save Changes'}
                </button>
                <button
                  type="button"
                  onClick={() => setEditing(false)}
                  className="border border-cocoa/30 px-6 py-3.5 text-xs uppercase tracking-[0.18em] text-cocoa transition-colors hover:border-cocoa"
                >
                  Cancel
                </button>
              </div>
            </form>
          ) : (
            <>
              <dl className="mt-6 max-w-md divide-y divide-cocoa/10 border-y border-cocoa/10">
                <ProfileRow label="Username" value={profile.username} />
                <ProfileRow label="Display name" value={profile.displayName || profile.username} />
                <ProfileRow
                  label="Name"
                  value={[profile.firstName, profile.lastName].filter(Boolean).join(' ') || '—'}
                />
                <ProfileRow label="Email" value={profile.email || '—'} />
                <ProfileRow label="Bio" value={profile.bio || '—'} />
              </dl>
              <button
                onClick={startEditing}
                className="mt-6 border border-cocoa px-8 py-3 text-xs uppercase tracking-[0.18em] text-cocoa transition-colors duration-300 hover:bg-cocoa hover:text-ivory"
              >
                Edit Profile
              </button>
            </>
          )}

          {profile && <PublicProfilePanel profile={profile} onChange={setProfile} />}
        </>
      )}
    </section>
  );
}

/**
 * Handle and visibility (docs/profile/public-profile.md step 8). Ported from
 * ui-shop's Profile page — same endpoints, this storefront's styling.
 *
 * Separate from the details form above because both calls have their own
 * endpoints and their own failure modes: the handle can 409, and publishing is a
 * switch rather than a text field.
 */
function PublicProfilePanel({ profile, onChange }: {
  profile: ProfileResponse;
  onChange: (profile: ProfileResponse) => void;
}) {
  const [handle, setHandle] = useState(profile.handle ?? '');
  const [availability, setAvailability] = useState<HandleAvailability | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const dirty = handle.trim().toLowerCase() !== (profile.handle ?? '');

  // Debounced so typing a handle does not fire a request per keystroke.
  useEffect(() => {
    if (!dirty || !handle.trim()) {
      setAvailability(null);
      return;
    }
    const timer = setTimeout(() => {
      api
        .checkHandle(handle.trim().toLowerCase())
        .then(setAvailability)
        .catch(() => setAvailability(null));
    }, 400);
    return () => clearTimeout(timer);
  }, [handle, dirty]);

  async function run(action: () => Promise<ProfileResponse>) {
    setBusy(true);
    setError(null);
    try {
      onChange(await action());
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  const url = profile.handle ? `${window.location.origin}/users/${profile.handle}` : null;

  return (
    <div className="mt-10 border-t border-cocoa/10 pt-8">
      <h2 className="font-display text-[24px] text-cocoa">Public profile</h2>
      <p className="mt-2 max-w-md text-sm text-cocoa/60">
        Pick a handle, then choose whether anyone can see your profile. Your handle stays
        yours even while your profile is private.
      </p>

      <div className="mt-5 flex max-w-md flex-wrap items-center gap-2">
        <span className="text-sm text-cocoa/50">/users/</span>
        <input
          value={handle}
          onChange={(e) => setHandle(e.target.value.toLowerCase())}
          placeholder="your-handle"
          maxLength={32}
          className={`${inputStyle} flex-1`}
        />
        <button
          type="button"
          disabled={busy || !dirty || !handle.trim()}
          onClick={() => run(() => api.setHandle(handle.trim().toLowerCase()))}
          className="border border-cocoa px-6 py-3 text-xs uppercase tracking-[0.18em] text-cocoa transition-colors hover:bg-cocoa hover:text-ivory disabled:cursor-not-allowed disabled:opacity-40"
        >
          Save handle
        </button>
      </div>

      {dirty && availability && (
        <p className="mt-2 text-sm text-cocoa/70">
          {availability.available ? `${availability.handle} is available` : availability.reason}
        </p>
      )}

      <div className="mt-5 flex flex-wrap items-center gap-3">
        <button
          type="button"
          disabled={busy || !profile.handle}
          onClick={() => run(() => api.setProfileVisibility(!profile.publicProfile))}
          title={profile.handle ? undefined : 'Choose a handle first'}
          className="bg-cocoa px-8 py-3.5 text-xs uppercase tracking-[0.18em] text-ivory transition-colors duration-300 hover:bg-espresso disabled:cursor-not-allowed disabled:opacity-40"
        >
          {profile.publicProfile ? 'Make my profile private' : 'Make my profile public'}
        </button>
        {!profile.handle && (
          <span className="text-sm text-cocoa/60">Choose a handle first.</span>
        )}
      </div>

      {profile.publicProfile && url && (
        <div className="mt-5 flex flex-wrap items-center gap-3">
          <code className="bg-cocoa/5 px-3 py-2 text-sm text-cocoa">{url}</code>
          <button
            type="button"
            onClick={() => {
              navigator.clipboard.writeText(url);
              setCopied(true);
              setTimeout(() => setCopied(false), 1500);
            }}
            className={pickerButton}
          >
            {copied ? 'Copied' : 'Copy link'}
          </button>
          <a href={`/users/${profile.handle}`} target="_blank" rel="noreferrer" className={pickerButton}>
            View as visitor
          </a>
        </div>
      )}

      {error && <p className="mt-3 text-sm text-terracotta">{error}</p>}
    </div>
  );
}

const pickerButton =
  'text-xs uppercase tracking-[0.14em] text-cocoa underline decoration-gold underline-offset-4 transition-colors hover:text-terracotta disabled:cursor-not-allowed disabled:opacity-40';

function AvatarPicker({ profile, onChange }: {
  profile: ProfileResponse;
  onChange: (profile: ProfileResponse) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  const displayName = profile.displayName || profile.username;
  const hasGoogle = profile.googlePictureUrl !== null;
  const hasUpload = profile.uploadedAvatarUrl !== null;

  async function run(action: () => Promise<ProfileResponse>) {
    setBusy(true);
    setError(null);
    try {
      onChange(await action());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  function onFileSelected(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (file) run(() => api.uploadAvatar(file));
  }

  return (
    <div className="mt-6 flex flex-wrap items-center gap-6 border-b border-cocoa/10 pb-8">
      <Avatar src={profile.avatarUrl} name={displayName} size={80} ring />

      <div className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center gap-5">
          <button type="button" disabled={busy} className={pickerButton}
            onClick={() => fileInput.current?.click()}>
            {busy ? 'Working…' : hasUpload ? 'Replace picture' : 'Upload a picture'}
          </button>

          {/* Only offered when there is something to switch to, so the buttons
              never lead to a 400 the customer can't act on. */}
          {hasGoogle && profile.avatarSource !== 'GOOGLE' && (
            <button type="button" disabled={busy} className={pickerButton}
              onClick={() => run(() => api.setAvatarSource('GOOGLE'))}>
              Use my Google picture
            </button>
          )}
          {hasUpload && profile.avatarSource !== 'UPLOAD' && (
            <button type="button" disabled={busy} className={pickerButton}
              onClick={() => run(() => api.setAvatarSource('UPLOAD'))}>
              Use my uploaded picture
            </button>
          )}
          {hasUpload && (
            <button type="button" disabled={busy}
              className={`${pickerButton} text-terracotta/80 decoration-terracotta/40`}
              onClick={() => run(() => api.removeAvatar())}>
              Remove upload
            </button>
          )}
          {profile.avatarSource !== 'NONE' && (
            <button type="button" disabled={busy} className={pickerButton}
              onClick={() => run(() => api.setAvatarSource('NONE'))}>
              Show initials
            </button>
          )}
        </div>

        <p className="text-xs text-cocoa/50">
          {profile.avatarSource === 'GOOGLE' && 'Showing your Google picture.'}
          {profile.avatarSource === 'UPLOAD' && 'Showing the picture you uploaded.'}
          {profile.avatarSource === 'NONE' && 'Showing your initials.'}
          {' '}JPEG, PNG or WebP — cropped to a square and resized before upload.
        </p>

        {error && <p className="text-xs text-terracotta">{error}</p>}
      </div>

      <input
        ref={fileInput}
        type="file"
        accept="image/jpeg,image/png,image/webp"
        onChange={onFileSelected}
        className="hidden"
      />
    </div>
  );
}

function ProfileRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="py-4">
      <dt className="text-xs uppercase tracking-[0.16em] text-cocoa/50">{label}</dt>
      <dd className="mt-1 text-sm text-cocoa">{value}</dd>
    </div>
  );
}
