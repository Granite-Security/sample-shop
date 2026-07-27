import { useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api } from '../api';
import { ApiError } from '../api/client';

export default function ResetPasswordConfirm() {
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
      await api.auth.confirmPasswordReset(token, newPassword);
      setSuccess(true);
    } catch (err) {
      // Same convention as Profile.tsx's password-change handler: the
      // backend's ProblemDetail "detail" (e.g. "This reset link is invalid
      // or has expired.") is already a friendly message.
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

  if (success) {
    return (
      <div className="page" style={{ maxWidth: 480 }}>
        <h1>Password reset</h1>
        <p>Your password has been changed. You can now log in with your new password.</p>
        <p style={{ marginTop: 16 }}>
          <Link to="/login" className="btn btn-primary">Log in</Link>
        </p>
      </div>
    );
  }

  return (
    <div className="page" style={{ maxWidth: 480 }}>
      <h1>Choose a new password</h1>

      <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 12, marginTop: 16 }}>
        <input
          type="password"
          value={newPassword}
          onChange={e => setNewPassword(e.target.value)}
          placeholder="New Password"
          autoComplete="new-password"
        />
        <input
          type="password"
          value={confirmPassword}
          onChange={e => setConfirmPassword(e.target.value)}
          placeholder="Confirm New Password"
          autoComplete="new-password"
        />
        <button className="btn btn-primary" type="submit" disabled={submitting}>
          {submitting ? 'Resetting…' : 'Reset password'}
        </button>
        {error && <p style={{ color: 'red' }}>{error}</p>}
      </form>
    </div>
  );
}
