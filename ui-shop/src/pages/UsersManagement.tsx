import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router';
import { useAuth } from '../auth';
import { api } from '../api';
import type { AdminUserView } from '../types';
import Avatar from '../components/Avatar';
import DeleteUserDialog from './DeleteUserDialog';

const SIGN_IN_LABELS: Record<AdminUserView['signInState'], { label: string; title: string }> = {
  LOCAL: { label: 'Password', title: 'Registered with the form; signs in with a password.' },
  // Not "Google": this account registered locally and later signed in with
  // Google. It still has a working password, and the password-change guard
  // deliberately still lets them use it (docs/users/blocking-users.md §2.2).
  LINKED: {
    label: 'Password + Google',
    title: 'Registered with the form, later linked a Google sign-in. Their password still works.',
  },
  GOOGLE: { label: 'Google', title: 'Provisioned by Google sign-in; has no password.' },
};

export default function UsersManagement() {
  const { isAdmin, user } = useAuth();
  const [users, setUsers] = useState<AdminUserView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<AdminUserView | null>(null);

  const currentUsername = user?.name ?? null;

  // A counter rather than a load() the effect calls: bumping it from an event
  // handler re-runs the fetch without the effect body itself setting state.
  const [reloadKey, setReloadKey] = useState(0);
  const reload = useCallback(() => setReloadKey(key => key + 1), []);

  useEffect(() => {
    if (!isAdmin) return;
    let cancelled = false;
    api.profile.getAdminUsers()
      .then(fetched => { if (!cancelled) setUsers(fetched); })
      .catch(e => { if (!cancelled) setError(e.message); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [isAdmin, reloadKey]);

  async function run(username: string, action: () => Promise<unknown>, success: string) {
    setBusy(username);
    setError(null);
    setNotice(null);
    try {
      await action();
      setNotice(success);
      reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(null);
    }
  }

  function handleDeleted(target: AdminUserView, result: { outcome: string; paidOrderCount: number; deletedOrderCount: number }) {
    setDeleting(null);
    // Never a silent partial success: if the user could not be deleted, say
    // exactly why (docs/users/blocking-users.md §8 Phase 5).
    if (result.outcome === 'BLOCKED_INSTEAD') {
      setNotice(
        `${target.username} has ${result.paidOrderCount} paid order(s), so their account was blocked ` +
        `rather than deleted. Their order history has to stay for the payment records to reconcile.`,
      );
    } else {
      setNotice(
        `${target.username} was deleted, along with ${result.deletedOrderCount} unpaid order(s).`,
      );
    }
    reload();
  }

  if (!isAdmin) {
    return (
      <div className="page">
        <h1>Access Denied</h1>
        <p>You do not have admin privileges.</p>
      </div>
    );
  }

  return (
    <div className="page">
      <h1>User Management</h1>

      {error && <p style={{ color: 'var(--danger)' }}>{error}</p>}
      {notice && (
        <p style={{
          padding: 12, borderRadius: 8, background: 'var(--surface)',
          border: '1px solid var(--border)',
        }}>{notice}</p>
      )}

      {loading ? (
        <p>Loading users...</p>
      ) : users.length === 0 ? (
        <p>No users found.</p>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {users.map(u => {
            const isSelf = u.username === currentUsername;
            const signIn = SIGN_IN_LABELS[u.signInState];
            const working = busy === u.username;
            return (
              <div key={u.username} style={{
                padding: 16, background: 'var(--surface)', borderRadius: 8,
                border: '1px solid var(--border)',
                opacity: u.enabled ? 1 : 0.7,
              }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                  <Avatar src={u.avatarUrl} name={u.displayName || u.username} size={32} />
                  <strong>{u.username}</strong>
                  <Badge
                    text={u.enabled ? 'Active' : 'Blocked'}
                    tone={u.enabled ? 'ok' : 'danger'}
                    title={u.enabled ? undefined
                      : `Blocked${u.blockedBy ? ` by ${u.blockedBy}` : ''}${u.blockedAt ? ` on ${new Date(u.blockedAt).toLocaleString()}` : ''}`}
                  />
                  <Badge text={signIn.label} tone="neutral" title={signIn.title} />
                  {u.roles.includes('ROLE_ADMIN') && <Badge text="Admin" tone="neutral" />}
                  {!u.hasProfile && (
                    <Badge
                      text="No profile"
                      tone="neutral"
                      title="This user exists in auth-server but has no profile row."
                    />
                  )}
                  <span style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                    {u.firstName} {u.lastName}
                  </span>
                </div>

                <p style={{ margin: '8px 0 4px', fontSize: '0.9rem' }}>{u.email}</p>

                <div style={{ display: 'flex', gap: 8, marginTop: 8, flexWrap: 'wrap' }}>
                  <Link to={`/admin/users/${u.username}`} className="btn">View profile</Link>

                  {u.enabled ? (
                    <button
                      className="btn"
                      disabled={isSelf || working}
                      title={isSelf ? 'You cannot block your own account.' : undefined}
                      onClick={() => run(u.username, () => api.profile.blockUser(u.username),
                        `${u.username} was blocked.`)}
                    >
                      {working ? 'Working…' : 'Block'}
                    </button>
                  ) : (
                    <button
                      className="btn"
                      disabled={working}
                      onClick={() => run(u.username, () => api.profile.unblockUser(u.username),
                        `${u.username} was unblocked.`)}
                    >
                      {working ? 'Working…' : 'Unblock'}
                    </button>
                  )}

                  {/* Takes a public profile down without touching the account —
                      for a bio or handle that has to go, where blocking would be
                      disproportionate (docs/profile/public-profile.md step 9).
                      Also releases the handle. Blocking already unpublishes on
                      its own, so this is the standalone case. */}
                  <button
                    className="btn"
                    disabled={working}
                    onClick={() => run(u.username, () => api.profile.unpublishUser(u.username),
                      `${u.username}'s public profile was taken down.`)}
                  >
                    {working ? 'Working…' : 'Unpublish'}
                  </button>

                  <button
                    className="btn"
                    disabled={isSelf || working}
                    title={isSelf ? 'You cannot delete your own account.' : undefined}
                    onClick={() => { setError(null); setNotice(null); setDeleting(u); }}
                  >
                    Delete
                  </button>

                  {/* Unbacked issuance: this conjures CHF out of nothing and is
                      authorized server-side by ROLE_ADMIN, not by this button
                      existing (docs/finance/finance.md D11). */}
                  <button
                    className="btn"
                    disabled={working}
                    onClick={() => {
                      const raw = window.prompt(`Gift how many CHF to ${u.username}?`, '10');
                      if (raw === null) return;
                      const amountChf = Number(raw);
                      if (!Number.isFinite(amountChf) || amountChf <= 0) {
                        setError('Enter a positive amount in CHF.');
                        return;
                      }
                      run(u.username,
                        () => api.balance.gift({
                          username: u.username,
                          amountChf,
                          reason: 'Admin gift',
                          idempotencyKey: crypto.randomUUID(),
                        }),
                        `Gifted CHF ${amountChf.toFixed(2)} to ${u.username}.`);
                    }}
                  >
                    Gift balance
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {deleting && (
        <DeleteUserDialog
          user={deleting}
          onCancel={() => setDeleting(null)}
          onDeleted={result => handleDeleted(deleting, result)}
          onError={message => { setDeleting(null); setError(message); }}
        />
      )}
    </div>
  );
}

function Badge({ text, tone, title }: {
  text: string;
  tone: 'ok' | 'danger' | 'neutral';
  title?: string;
}) {
  const colors = {
    ok: 'var(--success, #2e7d32)',
    danger: 'var(--danger)',
    neutral: 'var(--text-secondary)',
  };
  return (
    <span
      title={title}
      style={{
        fontSize: '0.75rem', padding: '2px 8px', borderRadius: 999,
        border: `1px solid ${colors[tone]}`, color: colors[tone],
        cursor: title ? 'help' : undefined,
      }}
    >
      {text}
    </span>
  );
}
