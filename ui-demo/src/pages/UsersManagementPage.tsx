import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router';
import { api } from '../api';
import { useAuth } from '../auth';
import type { AdminUserView } from '../types';
import { Avatar } from '../components/Avatar';
import { DeleteUserDialog } from './DeleteUserDialog';

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

export function UsersManagementPage() {
  const { isAdmin, user, loading: authLoading } = useAuth();
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
  const reload = useCallback(() => setReloadKey((key) => key + 1), []);

  useEffect(() => {
    if (!isAdmin) return;
    let cancelled = false;
    api.getAdminUsers()
      .then((fetched) => { if (!cancelled) setUsers(fetched); })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)); })
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

  if (authLoading) {
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
          This area is reserved for SI Chocolate staff. Sign in with an admin account to manage
          customer accounts.
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

  return (
    <div className="bg-ivory pt-28 lg:pt-32">
      <div className="mx-auto max-w-5xl px-5 pb-24 lg:px-8">
        <p className="text-xs uppercase tracking-[0.3em] text-terracotta">Back of House</p>
        <h1 className="mt-3 font-display text-[36px] leading-tight text-cocoa lg:text-[48px]">
          Customer Accounts
        </h1>
        <p className="mt-2 text-sm text-cocoa/60">
          Every account known to the boutique — block, unblock or delete them here.{' '}
          <Link to="/admin" className="text-cocoa underline decoration-gold underline-offset-4 hover:text-terracotta">
            Back to the collection
          </Link>
        </p>

        {error && (
          <p role="status" className="mt-6 border-l-2 border-terracotta bg-terracotta/10 px-4 py-3 text-sm text-terracotta">
            {error}
          </p>
        )}
        {notice && (
          <p role="status" className="mt-6 border-l-2 border-sage bg-sage/10 px-4 py-3 text-sm text-cocoa">
            {notice}
          </p>
        )}

        {loading ? (
          <p className="mt-10 text-sm text-cocoa/50">Loading users…</p>
        ) : users.length === 0 ? (
          <p className="mt-10 text-sm text-cocoa/50">No users found.</p>
        ) : (
          <ul className="mt-10 divide-y divide-cocoa/10 border-y border-cocoa/10">
            {users.map((u) => {
              const isSelf = u.username === currentUsername;
              const signIn = SIGN_IN_LABELS[u.signInState];
              const working = busy === u.username;
              return (
                <li key={u.username} className={`py-6 ${u.enabled ? '' : 'opacity-70'}`}>
                  <div className="flex flex-wrap items-center gap-2">
                    <Avatar src={u.avatarUrl} name={u.displayName || u.username} size={34} />
                    <span className="font-display text-lg text-cocoa">{u.username}</span>
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
                    <span className="text-sm text-cocoa/50">
                      {u.firstName} {u.lastName}
                    </span>
                  </div>

                  <p className="mt-2 text-sm text-cocoa/70">{u.email}</p>

                  <div className="mt-4 flex flex-wrap items-center gap-5">
                    <Link
                      to={`/admin/users/${u.username}`}
                      className="text-xs uppercase tracking-[0.14em] text-cocoa underline decoration-gold underline-offset-4 hover:text-terracotta"
                    >
                      View profile
                    </Link>

                    {u.enabled ? (
                      <button
                        disabled={isSelf || working}
                        title={isSelf ? 'You cannot block your own account.' : undefined}
                        onClick={() => run(u.username, () => api.blockUser(u.username),
                          `${u.username} was blocked.`)}
                        className="text-xs uppercase tracking-[0.14em] text-cocoa underline underline-offset-4 hover:text-terracotta disabled:cursor-not-allowed disabled:opacity-40"
                      >
                        {working ? 'Working…' : 'Block'}
                      </button>
                    ) : (
                      <button
                        disabled={working}
                        onClick={() => run(u.username, () => api.unblockUser(u.username),
                          `${u.username} was unblocked.`)}
                        className="text-xs uppercase tracking-[0.14em] text-cocoa underline underline-offset-4 hover:text-terracotta disabled:opacity-40"
                      >
                        {working ? 'Working…' : 'Unblock'}
                      </button>
                    )}

                    <button
                      disabled={isSelf || working}
                      title={isSelf ? 'You cannot delete your own account.' : undefined}
                      onClick={() => { setError(null); setNotice(null); setDeleting(u); }}
                      className="text-xs uppercase tracking-[0.14em] text-terracotta/80 underline underline-offset-4 hover:text-terracotta disabled:cursor-not-allowed disabled:opacity-40"
                    >
                      Delete
                    </button>
                  </div>
                </li>
              );
            })}
          </ul>
        )}

        {deleting && (
          <DeleteUserDialog
            user={deleting}
            onCancel={() => setDeleting(null)}
            onDeleted={(result) => handleDeleted(deleting, result)}
            onError={(message) => { setDeleting(null); setError(message); }}
          />
        )}
      </div>
    </div>
  );
}

const BADGE_TONES = {
  ok: 'border-sage text-cocoa',
  danger: 'border-terracotta text-terracotta',
  neutral: 'border-cocoa/30 text-cocoa/60',
};

function Badge({ text, tone, title }: {
  text: string;
  tone: keyof typeof BADGE_TONES;
  title?: string;
}) {
  return (
    <span
      title={title}
      className={`rounded-full border px-2.5 py-0.5 text-[10px] uppercase tracking-[0.12em] ${BADGE_TONES[tone]} ${
        title ? 'cursor-help' : ''
      }`}
    >
      {text}
    </span>
  );
}
