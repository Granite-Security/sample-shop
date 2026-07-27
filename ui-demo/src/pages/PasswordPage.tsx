import { useState, type FormEvent } from 'react';
import { api, ApiError } from '../api';

const inputStyle =
  'w-full border border-cocoa/20 bg-white/70 px-4 py-3 text-sm text-cocoa placeholder:text-cocoa/40 focus:border-gold focus:outline-none';

export function PasswordPage() {
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null);

  const handleChangePassword = async (e: FormEvent) => {
    e.preventDefault();
    setMessage(null);

    if (newPassword.length < 8) {
      setMessage({ kind: 'error', text: 'New password must be at least 8 characters' });
      return;
    }
    if (newPassword !== confirmPassword) {
      setMessage({ kind: 'error', text: 'New password and confirmation do not match' });
      return;
    }

    setBusy(true);
    try {
      await api.changePassword({ currentPassword, newPassword });
      setMessage({ kind: 'ok', text: 'Password changed. A confirmation email was sent to your address.' });
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
    } catch (err) {
      // The backend's ProblemDetail "detail" is already a friendly message
      // (e.g. "This account signs in with Google; there is no password to
      // change.") — surface it directly.
      if (err instanceof ApiError) {
        const detail = (err.data as { detail?: string } | undefined)?.detail;
        setMessage({ kind: 'error', text: detail ?? err.message });
      } else {
        setMessage({ kind: 'error', text: 'Failed to change password' });
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="bg-ivory pt-28 lg:pt-32">
      <div className="mx-auto max-w-3xl px-5 pb-24 lg:px-8">
        <p className="text-xs uppercase tracking-[0.3em] text-terracotta">Your Account</p>
        <h1 className="mt-3 font-display text-[36px] leading-tight text-cocoa lg:text-[48px]">Password</h1>

        <section aria-label="Password" className="mt-10">
          {message && (
            <p
              role="status"
              className={`mb-4 border-l-2 px-4 py-3 text-sm ${
                message.kind === 'ok' ? 'border-sage bg-sage/10 text-cocoa' : 'border-terracotta bg-terracotta/10 text-terracotta'
              }`}
            >
              {message.text}
            </p>
          )}
          <form onSubmit={handleChangePassword} className="flex max-w-sm flex-col gap-4">
            <input
              type="password"
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
              placeholder="Current password"
              autoComplete="current-password"
              className={inputStyle}
            />
            <input
              type="password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              placeholder="New password"
              autoComplete="new-password"
              className={inputStyle}
            />
            <input
              type="password"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              placeholder="Confirm new password"
              autoComplete="new-password"
              className={inputStyle}
            />
            <button
              type="submit"
              disabled={busy}
              className="bg-cocoa px-8 py-3.5 text-xs uppercase tracking-[0.18em] text-ivory transition-colors duration-300 hover:bg-espresso disabled:cursor-not-allowed disabled:opacity-40"
            >
              {busy ? 'Changing…' : 'Change Password'}
            </button>
          </form>
        </section>
      </div>
    </div>
  );
}
