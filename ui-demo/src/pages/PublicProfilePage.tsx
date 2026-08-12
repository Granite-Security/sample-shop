import { useEffect, useState } from 'react';
import { useParams } from 'react-router';
import { api } from '../api';
import { Avatar } from '../components/Avatar';
import { useAuth } from '../auth';
import { userManager } from '../oauth';
import type { PublicProfileResponse } from '../types';

const inputStyle =
  'w-full border border-cocoa/20 bg-white/70 px-4 py-3 text-sm text-cocoa placeholder:text-cocoa/40 focus:border-gold focus:outline-none';

const primaryButton =
  'bg-cocoa px-8 py-3.5 text-xs uppercase tracking-[0.18em] text-ivory transition-colors duration-300 hover:bg-espresso disabled:cursor-not-allowed disabled:opacity-40';

const outlineButton =
  'border border-cocoa px-6 py-3 text-xs uppercase tracking-[0.18em] text-cocoa transition-colors hover:bg-cocoa hover:text-ivory disabled:cursor-not-allowed disabled:opacity-40';

/**
 * The public profile at /users/<handle> (docs/profile/public-profile.md).
 *
 * This route lives outside RequireAuth on purpose — signed out is the case it
 * exists for. Nothing here may assume a session.
 *
 * `bio` and `displayName` are user-authored text rendered as JSX children so
 * React escapes them. Never dangerouslySetInnerHTML on this page.
 */
export function PublicProfilePage() {
  const { handle = '' } = useParams();
  const [profile, setProfile] = useState<PublicProfileResponse | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    api
      .getPublicProfile(handle)
      .then((p) => {
        if (!cancelled) setProfile(p);
      })
      .catch(() => {
        if (!cancelled) setProfile(null);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [handle]);

  if (loading) {
    return <p className="mx-auto max-w-3xl px-6 py-24 text-sm text-cocoa/50">Loading…</p>;
  }

  // One message for both an unknown handle and an unpublished profile — the
  // server does not distinguish them, and saying which would confirm the handle
  // exists (docs/profile/public-profile.md D4).
  if (!profile) {
    return (
      <div className="mx-auto max-w-3xl px-6 py-24">
        <h1 className="font-display text-[36px] leading-tight text-cocoa">Profile not available</h1>
        <p className="mt-3 text-sm text-cocoa/60">
          This profile doesn't exist, or its owner hasn't made it public.
        </p>
      </div>
    );
  }

  const name = profile.displayName || profile.handle;

  return (
    <div className="mx-auto max-w-3xl px-6 py-16">
      <div className="flex items-center gap-6">
        <Avatar src={profile.avatarUrl} name={name} size={96} ring />
        <div>
          <h1 className="font-display text-[36px] leading-tight text-cocoa">{name}</h1>
          <p className="mt-1 text-sm text-cocoa/60">@{profile.handle}</p>
          <p className="mt-1 text-xs uppercase tracking-[0.16em] text-cocoa/40">
            Member since {new Date(profile.memberSince).toLocaleDateString()}
          </p>
        </div>
      </div>

      {profile.bio && (
        <p className="mt-8 max-w-xl whitespace-pre-wrap text-sm leading-relaxed text-cocoa/80">
          {profile.bio}
        </p>
      )}

      <PublicActions profile={profile} />
    </div>
  );
}

/**
 * Message and Gift, both driven by `profile.username` against the endpoints that
 * already exist — publishing the username is what makes that possible with no
 * new resolution path or write surface (D3).
 */
function PublicActions({ profile }: { profile: PublicProfileResponse }) {
  const { isAuthenticated, user } = useAuth();
  const [panel, setPanel] = useState<'message' | 'gift' | null>(null);

  if (!isAuthenticated) {
    return (
      <div className="mt-10">
        <button
          type="button"
          className={primaryButton}
          onClick={() =>
            userManager.signinRedirect({
              // Come back here rather than to the storefront root.
              state: { returnTo: `/users/${profile.handle}` },
            })
          }
        >
          Sign in to message {profile.displayName || profile.handle}
        </button>
      </div>
    );
  }

  // Messaging and transferring to yourself are both rejected server-side; not
  // offering the buttons is friendlier than showing the error.
  if (user?.claims?.sub === profile.username) {
    return <p className="mt-10 text-sm text-cocoa/60">This is how other people see your profile.</p>;
  }

  return (
    <div className="mt-10">
      <div className="flex flex-wrap gap-3">
        <button type="button" className={outlineButton} onClick={() => setPanel(panel === 'message' ? null : 'message')}>
          Message
        </button>
        <button type="button" className={outlineButton} onClick={() => setPanel(panel === 'gift' ? null : 'gift')}>
          Send a gift
        </button>
      </div>
      {panel === 'message' && <MessageForm to={profile.username} onDone={() => setPanel(null)} />}
      {panel === 'gift' && <GiftForm to={profile.username} onDone={() => setPanel(null)} />}
    </div>
  );
}

function MessageForm({ to, onDone }: { to: string; onDone: () => void }) {
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);

  async function send() {
    setBusy(true);
    setError(null);
    try {
      await api.sendMessage({ to, subject, body });
      setSent(true);
      setTimeout(onDone, 1200);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  if (sent) return <p className="mt-4 text-sm text-cocoa/70">Message sent.</p>;

  return (
    <div className="mt-5 max-w-xl space-y-3">
      <input
        value={subject}
        onChange={(e) => setSubject(e.target.value)}
        placeholder="Subject (optional)"
        className={inputStyle}
      />
      <textarea
        rows={4}
        value={body}
        onChange={(e) => setBody(e.target.value)}
        placeholder="Your message"
        className={inputStyle}
      />
      {error && <p className="text-sm text-terracotta">{error}</p>}
      <div className="flex gap-3">
        <button type="button" className={primaryButton} disabled={busy || !body.trim()} onClick={send}>
          {busy ? 'Sending…' : 'Send'}
        </button>
        <button type="button" className={outlineButton} onClick={onDone}>
          Cancel
        </button>
      </div>
    </div>
  );
}

function GiftForm({ to, onDone }: { to: string; onDone: () => void }) {
  const [amount, setAmount] = useState('');
  const [memo, setMemo] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);
  // Fixed for the lifetime of this form, so a double click or a retry after a
  // timeout replays the original transfer instead of moving the money twice.
  const [idempotencyKey] = useState(() => crypto.randomUUID());

  async function send() {
    setBusy(true);
    setError(null);
    try {
      await api.transferBalance({ to, amountChf: Number(amount), memo, idempotencyKey });
      setSent(true);
      setTimeout(onDone, 1200);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  if (sent) return <p className="mt-4 text-sm text-cocoa/70">Gift sent.</p>;

  return (
    <div className="mt-5 max-w-xl space-y-3">
      <input
        type="number"
        min="0.05"
        step="0.05"
        inputMode="decimal"
        value={amount}
        onChange={(e) => setAmount(e.target.value)}
        placeholder="Amount in CHF"
        className={inputStyle}
      />
      <input
        value={memo}
        onChange={(e) => setMemo(e.target.value)}
        placeholder="Note (optional)"
        className={inputStyle}
      />
      {error && <p className="text-sm text-terracotta">{error}</p>}
      <div className="flex gap-3">
        <button type="button" className={primaryButton} disabled={busy || !(Number(amount) > 0)} onClick={send}>
          {busy ? 'Sending…' : 'Send gift'}
        </button>
        <button type="button" className={outlineButton} onClick={onDone}>
          Cancel
        </button>
      </div>
    </div>
  );
}
