import { useState, type FormEvent } from 'react';
import { Link, useLocation } from 'react-router';
import { api } from '../api';

export default function ResetPasswordRequest() {
  const location = useLocation();
  const prefillEmail = (location.state as { email?: string } | null)?.email ?? '';

  const [email, setEmail] = useState(prefillEmail);
  const [submitting, setSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      await api.auth.requestPasswordReset(email);
    } catch {
      // Deliberately ignored — the backend always responds 200 regardless
      // of whether the email matched an account, so there's nothing more
      // specific to show. A network-level failure just gets the same
      // generic message; the user can retry.
    } finally {
      setSubmitting(false);
      setSubmitted(true);
    }
  };

  if (submitted) {
    return (
      <div className="page" style={{ maxWidth: 480 }}>
        <h1>Check your email</h1>
        <p>If an account exists for that email address, we've sent a link to reset your password.</p>
        <p style={{ marginTop: 16 }}>
          <Link to="/login">Back to login</Link>
        </p>
      </div>
    );
  }

  return (
    <div className="page" style={{ maxWidth: 480 }}>
      <h1>Reset your password</h1>
      <p>Enter the email address on your account and we'll send you a link to reset your password.</p>

      <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 12, marginTop: 16 }}>
        <input
          type="email"
          value={email}
          onChange={e => setEmail(e.target.value)}
          placeholder="Email"
          autoComplete="email"
          required
        />
        <button className="btn btn-primary" type="submit" disabled={submitting}>
          {submitting ? 'Sending…' : 'Send reset link'}
        </button>
      </form>

      <p style={{ marginTop: 16 }}>
        <Link to="/login">Back to login</Link>
      </p>
    </div>
  );
}
