import { useEffect, useState } from 'react';
import { api } from '../api';
import type { AdminUserView, DeleteUserResult } from '../types';

/**
 * Deleting a user is irreversible and cascades into their orders, so this asks
 * for the username to be typed out and shows the order count *before* the
 * button is live (docs/users/blocking-users.md §8 Phase 5). Mirrors
 * ui-shop/src/pages/DeleteUserDialog.tsx.
 */
export function DeleteUserDialog({ user, onCancel, onDeleted, onError }: {
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
    api.getOrdersByUsername(user.username, 0, 1)
      .then((page) => { if (!cancelled) setOrderCount(page.total); })
      .catch(() => { if (!cancelled) setCountError(true); });
    return () => { cancelled = true; };
  }, [user.username]);

  const confirmed = typed === user.username;

  async function submit() {
    setSubmitting(true);
    try {
      onDeleted(await api.deleteUser(user.username));
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
      className="fixed inset-0 z-[100] flex items-center justify-center bg-espresso/60 p-4"
      onClick={onCancel}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-md border border-cocoa/15 bg-ivory p-8"
      >
        <h2 id="delete-user-title" className="font-display text-[24px] text-cocoa">
          Delete {user.username}?
        </h2>

        <p className="mt-3 text-sm text-cocoa/70">
          This permanently removes the account, its profile and delivery addresses. It cannot be
          undone.
        </p>

        {countError ? (
          <p className="mt-3 text-sm text-terracotta">Could not load this customer's order count.</p>
        ) : orderCount === null ? (
          <p className="mt-3 text-sm text-cocoa/50">Checking their orders…</p>
        ) : (
          <p className="mt-3 text-sm text-cocoa/70">
            They have <strong className="text-cocoa">{orderCount}</strong> order(s). Unpaid orders
            are deleted with them.
          </p>
        )}

        {/* Stated up front so the outcome is never a surprise. */}
        <p className="mt-3 text-xs text-cocoa/50">
          If any of their orders were actually paid, the account is{' '}
          <strong className="text-cocoa">blocked</strong> instead of deleted — the order history has
          to stay for the payment records to reconcile.
        </p>

        <label className="mt-6 block text-xs uppercase tracking-[0.16em] text-cocoa/60">
          Type <strong className="normal-case tracking-normal text-cocoa">{user.username}</strong> to confirm:
          <input
            autoFocus
            value={typed}
            onChange={(e) => setTyped(e.target.value)}
            className="mt-2 w-full border border-cocoa/20 bg-white/70 px-4 py-3 text-sm normal-case tracking-normal text-cocoa focus:border-gold focus:outline-none"
          />
        </label>

        <div className="mt-6 flex justify-end gap-3">
          <button
            onClick={onCancel}
            disabled={submitting}
            className="border border-cocoa/30 px-6 py-3 text-xs uppercase tracking-[0.18em] text-cocoa transition-colors hover:border-cocoa disabled:opacity-40"
          >
            Cancel
          </button>
          <button
            disabled={!confirmed || submitting}
            onClick={submit}
            className="border border-terracotta px-6 py-3 text-xs uppercase tracking-[0.18em] text-terracotta transition-colors duration-300 hover:bg-terracotta hover:text-ivory disabled:cursor-not-allowed disabled:opacity-40"
          >
            {submitting ? 'Deleting…' : 'Delete user'}
          </button>
        </div>
      </div>
    </div>
  );
}
