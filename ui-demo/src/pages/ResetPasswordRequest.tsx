import { useState, type FormEvent } from 'react';
import { Link, useLocation } from 'react-router';
import { api } from '../api';

// Ported from ui-shop/src/pages/ResetPasswordRequest.tsx.
const inputStyle =
  'w-full border border-cocoa/20 bg-white/70 px-4 py-3 text-sm text-cocoa placeholder:text-cocoa/40 focus:border-gold focus:outline-none';

export function ResetPasswordRequest() {
  const location = useLocation();
  const prefillEmail = (location.state as { email?: string } | null)?.email ?? '';

  const [email, setEmail] = useState(prefillEmail);
  const [submitting, setSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      await api.requestPasswordReset(email);
    } catch {
      // Deliberately ignored — the backend always responds 200 regardless
      // of whether the email matched an account, so there's nothing more
      // specific to show.
    } finally {
      setSubmitting(false);
      setSubmitted(true);
    }
  };

  return (
    <div className="bg-ivory pt-28 lg:pt-32">
      <div className="mx-auto max-w-xl px-5 pb-24 lg:px-8">
        <p className="text-xs uppercase tracking-[0.3em] text-terracotta">Account Recovery</p>
        <h1 className="mt-3 font-display text-[36px] leading-tight text-cocoa lg:text-[48px]">
          {submitted ? 'Check Your Email' : 'Reset Your Password'}
        </h1>

        {submitted ? (
          <>
            <p className="mt-6 text-cocoa/70">
              If an account exists for that email address, we've sent a link to reset your password.
            </p>
            <Link
              to="/"
              className="mt-8 inline-block border border-cocoa px-8 py-3 text-xs uppercase tracking-[0.18em] text-cocoa transition-colors duration-300 hover:bg-cocoa hover:text-ivory"
            >
              Back to the Boutique
            </Link>
          </>
        ) : (
          <>
            <p className="mt-4 text-cocoa/70">
              Enter the email address on your account and we'll send you a link to reset your password.
            </p>
            <form onSubmit={handleSubmit} className="mt-8 flex flex-col gap-4">
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="Email"
                autoComplete="email"
                required
                className={inputStyle}
              />
              <button
                type="submit"
                disabled={submitting}
                className="bg-cocoa py-3.5 text-xs uppercase tracking-[0.18em] text-ivory transition-colors duration-300 hover:bg-espresso disabled:cursor-not-allowed disabled:opacity-40"
              >
                {submitting ? 'Sending…' : 'Send Reset Link'}
              </button>
            </form>
          </>
        )}
      </div>
    </div>
  );
}
