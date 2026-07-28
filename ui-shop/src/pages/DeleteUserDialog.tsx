import { useEffect, useState } from 'react';
import { api } from '../api';
import type { AdminUserView, DeleteUserResult } from '../types';

/**
 * Deleting a user is irreversible and cascades into their orders, so this asks
 * for the username to be typed out and shows the order count *before* the
 * button is live (docs/users/blocking-users.md §8 Phase 5).
 */
export default function DeleteUserDialog({ user, onCancel, onDeleted, onError }: {
  user: AdminUserView;
  onCancel: () => void;
  onDeleted: (result: DeleteUserResult) => void;
  onError: (message: string) => void;
}) {
  const [typed, setTyped] = useState('');
  const [orderCount, setOrderCount] = useState<number | null>(null);
  const [countError, setCountError] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api.orders.getOrdersByUsername(user.username, 0, 1)
      .then(page => { if (!cancelled) setOrderCount(page.total); })
      .catch(() => { if (!cancelled) setCountError(true); });
    return () => { cancelled = true; };
  }, [user.username]);

  const confirmed = typed === user.username;

  async function submit() {
    setSubmitting(true);
    try {
      onDeleted(await api.profile.deleteUser(user.username));
    } catch (e) {
      onError(e instanceof Error ? e.message : String(e));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="delete-user-title"
      style={{
        position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)',
        display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 16, zIndex: 100,
      }}
      onClick={onCancel}
    >
      <div
        onClick={e => e.stopPropagation()}
        style={{
          background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 8,
          padding: 24, maxWidth: 480, width: '100%',
        }}
      >
        <h2 id="delete-user-title" style={{ marginTop: 0 }}>Delete {user.username}?</h2>

        <p style={{ fontSize: '0.9rem' }}>
          This permanently removes the account, its profile and delivery addresses. It cannot be
          undone.
        </p>

        {countError ? (
          <p style={{ fontSize: '0.9rem', color: 'var(--danger)' }}>
            Could not load this user's order count.
          </p>
        ) : orderCount === null ? (
          <p style={{ fontSize: '0.9rem' }}>Checking their orders…</p>
        ) : (
          <p style={{ fontSize: '0.9rem' }}>
            They have <strong>{orderCount}</strong> order(s). Unpaid orders are deleted with them.
          </p>
        )}

        {/* Stated up front so the outcome is never a surprise. */}
        <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
          If any of their orders were actually paid, the account is <strong>blocked</strong> instead
          of deleted — the order history has to stay for the payment records to reconcile.
        </p>

        <label style={{ display: 'block', fontSize: '0.85rem', marginTop: 16 }}>
          Type <strong>{user.username}</strong> to confirm:
          <input
            autoFocus
            value={typed}
            onChange={e => setTyped(e.target.value)}
            style={{ display: 'block', width: '100%', marginTop: 4 }}
          />
        </label>

        <div style={{ display: 'flex', gap: 8, marginTop: 20, justifyContent: 'flex-end' }}>
          <button className="btn" onClick={onCancel} disabled={submitting}>Cancel</button>
          <button
            className="btn"
            disabled={!confirmed || submitting}
            style={{ borderColor: 'var(--danger)', color: 'var(--danger)' }}
            onClick={submit}
          >
            {submitting ? 'Deleting…' : 'Delete user'}
          </button>
        </div>
      </div>
    </div>
  );
}
