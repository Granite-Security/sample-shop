import { useEffect, useState } from 'react';
import { useParams } from 'react-router';
import { api } from '../api';
import { ApiError } from '../api/client';
import { useAuth } from '../auth';
import { userManager } from '../oauth';
import Avatar from '../components/Avatar';
import type { PublicFile, PublicProfileResponse } from '../types';

/**
 * The public profile at /users/<handle> (docs/profile/public-profile.md).
 *
 * This route lives outside RequireAuth on purpose — signed out is the case it
 * exists for. Nothing here may assume a session.
 *
 * `bio` and `displayName` are user-authored text rendered as JSX children so
 * React escapes them. Never dangerouslySetInnerHTML on this page.
 */
export default function PublicProfile() {
  const { handle = '' } = useParams();
  const [profile, setProfile] = useState<PublicProfileResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);

  useEffect(() => {
    setLoading(true);
    setNotFound(false);
    api.publicProfile.get(handle)
      .then(setProfile)
      .catch(() => setNotFound(true))
      .finally(() => setLoading(false));
  }, [handle]);

  if (loading) return <div className="spinner" style={{ margin: '0 auto' }} />;

  // One message for both an unknown handle and an unpublished profile — the
  // server does not distinguish them, and saying which would confirm the handle
  // exists (docs/profile/public-profile.md D4).
  if (notFound || !profile) {
    return (
      <div className="page">
        <h1>Profile not available</h1>
        <p>This profile doesn't exist, or its owner hasn't made it public.</p>
      </div>
    );
  }

  const name = profile.displayName || profile.handle;

  return (
    <div className="page" style={{ maxWidth: 640 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
        <Avatar src={profile.avatarUrl} name={name} size={96} />
        <div>
          <h1 style={{ margin: 0 }}>{name}</h1>
          <p style={{ margin: '4px 0', opacity: 0.7 }}>@{profile.handle}</p>
          <p style={{ margin: 0, fontSize: 13, opacity: 0.6 }}>
            Member since {new Date(profile.memberSince).toLocaleDateString()}
          </p>
        </div>
      </div>

      {profile.bio && (
        <p style={{ marginTop: 20, whiteSpace: 'pre-wrap' }}>{profile.bio}</p>
      )}

      <PublicFiles handle={profile.handle} />

      <PublicActions profile={profile} />
    </div>
  );
}

/**
 * An explicit allow-list, not a `contentType.startsWith('image/')` check.
 *
 * These are exactly the image types the upload path accepts today
 * (UserFileService.ALLOWED_CONTENT_TYPES). A prefix test would start inlining
 * `image/svg+xml` the day SVG is added to that list — and an SVG executes script,
 * which is the whole point of guardrail 1 in docs/todo/guardrails.md. Adding a
 * type here has to be a decision, not a side effect.
 */
const PREVIEWABLE = new Set(['image/jpeg', 'image/png', 'image/webp']);

function formatSize(bytes: number | null): string {
  if (bytes == null) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * Files the owner published (docs/profile/public-profile.md §11).
 *
 * Fetched separately from the profile so the page renders without waiting on it,
 * and so PublicProfileResponse does not grow a nested list. An empty result is
 * the common case — it renders nothing rather than an empty heading.
 */
function PublicFiles({ handle }: { handle: string }) {
  const [files, setFiles] = useState<PublicFile[]>([]);

  useEffect(() => {
    api.publicProfile.files(handle).then(setFiles).catch(() => setFiles([]));
  }, [handle]);

  if (files.length === 0) return null;

  return (
    <div style={{ marginTop: 28 }}>
      <h2 style={{ fontSize: 18 }}>Files</h2>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 8 }}>
        {files.map(file => (
          <div key={file.id} style={{
            display: 'flex', alignItems: 'center', gap: 12,
            padding: 8, border: '1px solid var(--border)', borderRadius: 4,
          }}>
            {PREVIEWABLE.has(file.contentType) && (
              <a href={file.url} target="_blank" rel="noreferrer" style={{ flexShrink: 0 }}>
                <img
                  src={file.url}
                  alt={file.fileName}
                  loading="lazy"
                  style={{
                    width: 64, height: 64, objectFit: 'cover',
                    borderRadius: 4, display: 'block',
                  }}
                  // A published file can be deleted from storage while the row
                  // lives on; a broken-image icon is worse than no thumbnail.
                  onError={e => { e.currentTarget.style.display = 'none'; }}
                />
              </a>
            )}
            <div>
              <a href={file.url} target="_blank" rel="noreferrer">{file.fileName}</a>
              <div style={{ fontSize: 12, opacity: 0.6 }}>
                {formatSize(file.sizeBytes)} · {new Date(file.createdAt).toLocaleDateString()}
              </div>
            </div>
          </div>
        ))}
      </div>
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
      <div style={{ marginTop: 24 }}>
        <button
          className="btn btn-primary"
          onClick={() => userManager.signinRedirect({
            // Come back here rather than to the home page.
            state: { returnTo: `/users/${profile.handle}` },
          })}
        >
          Sign in to message {profile.displayName || profile.handle}
        </button>
      </div>
    );
  }

  // Messaging and transferring to yourself are both rejected server-side; not
  // offering the buttons is friendlier than showing the error.
  const isSelf = user?.claims?.sub === profile.username;
  if (isSelf) {
    return (
      <p style={{ marginTop: 24, opacity: 0.7 }}>
        This is how other people see your profile.
      </p>
    );
  }

  return (
    <div style={{ marginTop: 24 }}>
      <div style={{ display: 'flex', gap: 8 }}>
        <button className="btn" onClick={() => setPanel(panel === 'message' ? null : 'message')}>
          Message
        </button>
        <button className="btn" onClick={() => setPanel(panel === 'gift' ? null : 'gift')}>
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

  const send = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.messages.send({ to, subject, body });
      setSent(true);
      setTimeout(onDone, 1200);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not send the message');
    } finally {
      setBusy(false);
    }
  };

  if (sent) return <p style={{ marginTop: 12 }}>Message sent.</p>;

  return (
    <div style={{ marginTop: 12, display: 'flex', flexDirection: 'column', gap: 8 }}>
      <input value={subject} onChange={e => setSubject(e.target.value)} placeholder="Subject (optional)" />
      <textarea value={body} onChange={e => setBody(e.target.value)} rows={4} placeholder="Your message" />
      {error && <p className="error">{error}</p>}
      <div style={{ display: 'flex', gap: 8 }}>
        <button className="btn btn-primary" disabled={busy || !body.trim()} onClick={send}>
          {busy ? 'Sending...' : 'Send'}
        </button>
        <button className="btn" onClick={onDone}>Cancel</button>
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

  const send = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.balance.transfer({ to, amountChf: Number(amount), memo, idempotencyKey });
      setSent(true);
      setTimeout(onDone, 1200);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not send the gift');
    } finally {
      setBusy(false);
    }
  };

  if (sent) return <p style={{ marginTop: 12 }}>Gift sent.</p>;

  return (
    <div style={{ marginTop: 12, display: 'flex', flexDirection: 'column', gap: 8 }}>
      <input
        type="number" min="0.05" step="0.05" inputMode="decimal"
        value={amount} onChange={e => setAmount(e.target.value)} placeholder="Amount in CHF"
      />
      <input value={memo} onChange={e => setMemo(e.target.value)} placeholder="Note (optional)" />
      {error && <p className="error">{error}</p>}
      <div style={{ display: 'flex', gap: 8 }}>
        <button className="btn btn-primary" disabled={busy || !(Number(amount) > 0)} onClick={send}>
          {busy ? 'Sending...' : 'Send gift'}
        </button>
        <button className="btn" onClick={onDone}>Cancel</button>
      </div>
    </div>
  );
}
