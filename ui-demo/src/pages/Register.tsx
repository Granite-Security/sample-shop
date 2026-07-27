import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { useAuth } from '../auth';
import { userManager } from '../oauth';
import { api, ApiError } from '../api';

// Ported from ui-shop/src/pages/Register.tsx, restyled with the cocoa
// palette used throughout ui-demo (see ProfilePage.tsx / CheckoutPage.tsx).
const USERNAME_PATTERN = /^[a-zA-Z0-9._-]+$/;

const inputStyle =
  'w-full border border-cocoa/20 bg-white/70 px-4 py-3 text-sm text-cocoa placeholder:text-cocoa/40 focus:border-gold focus:outline-none';

export function Register() {
  const { isAuthenticated, loading, login } = useAuth();
  const navigate = useNavigate();

  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');

  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [success, setSuccess] = useState(false);
  const [showForgotPassword, setShowForgotPassword] = useState(false);

  useEffect(() => {
    if (!loading && isAuthenticated) {
      navigate('/', { replace: true });
    }
  }, [isAuthenticated, loading, navigate]);

  useEffect(() => {
    if (!success) return;
    const timer = setTimeout(() => userManager.signinRedirect(), 1500);
    return () => clearTimeout(timer);
  }, [success]);

  const validate = (): Record<string, string> => {
    const errors: Record<string, string> = {};
    if (username.length < 3 || username.length > 64) {
      errors.username = 'Username must be 3-64 characters';
    } else if (!USERNAME_PATTERN.test(username)) {
      errors.username = 'Username may only contain letters, numbers, dots, dashes and underscores';
    }
    if (!email.includes('@')) {
      errors.email = 'Enter a valid email address';
    }
    if (password.length < 8 || password.length > 72) {
      errors.password = 'Password must be 8-72 characters';
    }
    if (password !== confirmPassword) {
      errors.confirmPassword = 'Passwords do not match';
    }
    return errors;
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setFormError(null);

    const errors = validate();
    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors);
      return;
    }
    setFieldErrors({});
    setShowForgotPassword(false);
    setSubmitting(true);

    try {
      await api.register({
        username,
        email,
        password,
        firstName: firstName || undefined,
        lastName: lastName || undefined,
      });
      setSuccess(true);
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        const data = err.data as { field?: string; detail?: string };
        if (data.field) {
          setFieldErrors({ [data.field]: data.detail ?? 'Already taken' });
          if (data.field === 'email') {
            setShowForgotPassword(true);
          }
        } else {
          setFormError(data.detail ?? err.message);
        }
      } else if (err instanceof ApiError && err.status === 400) {
        const data = err.data as { errors?: Record<string, string> };
        setFieldErrors(data.errors ?? {});
      } else {
        setFormError(err instanceof Error ? err.message : 'Registration failed');
      }
    } finally {
      setSubmitting(false);
    }
  };

  if (loading || isAuthenticated) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-ivory pt-28 text-cocoa/50">Loading…</div>
    );
  }

  return (
    <div className="bg-ivory pt-28 lg:pt-32">
      <div className="mx-auto max-w-xl px-5 pb-24 lg:px-8">
        <p className="text-xs uppercase tracking-[0.3em] text-terracotta">Join Us</p>
        <h1 className="mt-3 font-display text-[36px] leading-tight text-cocoa lg:text-[48px]">
          {success ? 'Account Created' : 'Create an Account'}
        </h1>

        {success ? (
          <p className="mt-6 text-cocoa/70">Redirecting you to sign in with your new credentials…</p>
        ) : (
          <>
            <form onSubmit={handleSubmit} className="mt-10 flex flex-col gap-4">
              {formError && (
                <p className="border-l-2 border-terracotta bg-terracotta/10 px-4 py-3 text-sm text-terracotta">
                  {formError}
                </p>
              )}

              <div>
                <input
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  placeholder="Username"
                  autoComplete="username"
                  className={inputStyle}
                />
                {fieldErrors.username && <p className="mt-1 text-sm text-terracotta">{fieldErrors.username}</p>}
              </div>

              <div>
                <input
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="Email"
                  autoComplete="email"
                  className={inputStyle}
                />
                {fieldErrors.email && <p className="mt-1 text-sm text-terracotta">{fieldErrors.email}</p>}
                {showForgotPassword && (
                  <p className="mt-1 text-sm">
                    <Link
                      to="/reset-password"
                      state={{ email }}
                      className="text-cocoa underline decoration-gold underline-offset-4 hover:text-terracotta"
                    >
                      Forgot password?
                    </Link>
                  </p>
                )}
              </div>

              <div>
                <input
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="Password"
                  autoComplete="new-password"
                  className={inputStyle}
                />
                {fieldErrors.password && <p className="mt-1 text-sm text-terracotta">{fieldErrors.password}</p>}
              </div>

              <div>
                <input
                  type="password"
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  placeholder="Confirm password"
                  autoComplete="new-password"
                  className={inputStyle}
                />
                {fieldErrors.confirmPassword && (
                  <p className="mt-1 text-sm text-terracotta">{fieldErrors.confirmPassword}</p>
                )}
              </div>

              <input
                value={firstName}
                onChange={(e) => setFirstName(e.target.value)}
                placeholder="First Name (optional)"
                autoComplete="given-name"
                className={inputStyle}
              />

              <input
                value={lastName}
                onChange={(e) => setLastName(e.target.value)}
                placeholder="Last Name (optional)"
                autoComplete="family-name"
                className={inputStyle}
              />

              <button
                type="submit"
                disabled={submitting}
                className="mt-2 bg-cocoa py-3.5 text-xs uppercase tracking-[0.18em] text-ivory transition-colors duration-300 hover:bg-espresso disabled:cursor-not-allowed disabled:opacity-40"
              >
                {submitting ? 'Creating account…' : 'Create Account'}
              </button>
            </form>

            <p className="mt-8 text-sm text-cocoa/70">
              Already have an account?{' '}
              <button
                onClick={login}
                className="text-cocoa underline decoration-gold underline-offset-4 hover:text-terracotta"
              >
                Sign in
              </button>
            </p>
          </>
        )}
      </div>
    </div>
  );
}
