import { useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';

// Ported from ui-shop/src/pages/ResetPasswordConfirm.tsx.
const inputStyle =
  'w-full border border-cocoa/20 bg-white/70 px-4 py-3 text-sm text-cocoa placeholder:text-cocoa/40 focus:border-gold focus:outline-none';

export function ResetPasswordConfirm() {
  const { login } = useAuth();
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token') ?? '';

  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);

    if (!token) {
      setError('This reset link is missing its token — please use the link from your email.');
      return;
    }
    if (newPassword.length < 8) {
      setError('New password must be at least 8 characters');
      return;
    }
    if (newPassword !== confirmPassword) {
      setError('New password and confirmation do not match');
      return;
    }

    setSubmitting(true);
    try {
      await api.confirmPasswordReset(token, newPassword);
      setSuccess(true);
    } catch (err) {
      // Same convention as ProfilePage.tsx's password-change handler: the
      // backend's ProblemDetail "detail" is already a friendly message.
      if (err instanceof ApiError) {
        const detail = (err.data as { detail?: string } | undefined)?.detail;
        setError(detail ?? err.message);
      } else {
        setError('Failed to reset password');
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="bg-ivory pt-28 lg:pt-32">
      <div className="mx-auto max-w-xl px-5 pb-24 lg:px-8">
        <p className="text-xs uppercase tracking-[0.3em] text-terracotta">Account Recovery</p>
        <h1 className="mt-3 font-display text-[36px] leading-tight text-cocoa lg:text-[48px]">
          {success ? 'Password Reset' : 'Choose a New Password'}
        </h1>

        {success ? (
          <>
            <p className="mt-6 text-cocoa/70">Your password has been changed. You can now log in with your new password.</p>
            <button
              onClick={login}
              className="mt-8 inline-block bg-cocoa px-8 py-3.5 text-xs uppercase tracking-[0.18em] text-ivory transition-colors duration-300 hover:bg-espresso"
            >
              Log In
            </button>
          </>
        ) : (
          <form onSubmit={handleSubmit} className="mt-8 flex flex-col gap-4">
            <input
              type="password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              placeholder="New Password"
              autoComplete="new-password"
              className={inputStyle}
            />
            <input
              type="password"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              placeholder="Confirm New Password"
              autoComplete="new-password"
              className={inputStyle}
            />
            <button
              type="submit"
              disabled={submitting}
              className="bg-cocoa py-3.5 text-xs uppercase tracking-[0.18em] text-ivory transition-colors duration-300 hover:bg-espresso disabled:cursor-not-allowed disabled:opacity-40"
            >
              {submitting ? 'Resetting…' : 'Reset Password'}
            </button>
            {error && (
              <p className="border-l-2 border-terracotta bg-terracotta/10 px-4 py-3 text-sm text-terracotta">
                {error}
              </p>
            )}
          </form>
        )}
      </div>
    </div>
  );
}
