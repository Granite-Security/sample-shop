import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { useAuth } from '../auth';
import { userManager } from '../oauth';
import { api } from '../api';
import { ApiError } from '../api/client';

const USERNAME_PATTERN = /^[a-zA-Z0-9._-]+$/;

export default function Register() {
  const { isAuthenticated, loading } = useAuth();
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
    setSubmitting(true);

    try {
      await api.auth.register({
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
    return <div className="page"><div className="spinner" style={{ margin: '0 auto' }} /></div>;
  }

  if (success) {
    return (
      <div className="page" style={{ maxWidth: 480 }}>
        <h1>Account created</h1>
        <p>Redirecting you to sign in with your new credentials...</p>
      </div>
    );
  }

  return (
    <div className="page" style={{ maxWidth: 480 }}>
      <h1>Create an account</h1>

      <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 12, marginTop: 16 }}>
        {formError && <p style={{ color: 'var(--error, red)' }}>{formError}</p>}

        <div>
          <input
            value={username}
            onChange={e => setUsername(e.target.value)}
            placeholder="Username"
            autoComplete="username"
          />
          {fieldErrors.username && <p style={{ color: 'var(--error, red)', margin: '4px 0 0' }}>{fieldErrors.username}</p>}
        </div>

        <div>
          <input
            type="email"
            value={email}
            onChange={e => setEmail(e.target.value)}
            placeholder="Email"
            autoComplete="email"
          />
          {fieldErrors.email && <p style={{ color: 'var(--error, red)', margin: '4px 0 0' }}>{fieldErrors.email}</p>}
        </div>

        <div>
          <input
            type="password"
            value={password}
            onChange={e => setPassword(e.target.value)}
            placeholder="Password"
            autoComplete="new-password"
          />
          {fieldErrors.password && <p style={{ color: 'var(--error, red)', margin: '4px 0 0' }}>{fieldErrors.password}</p>}
        </div>

        <div>
          <input
            type="password"
            value={confirmPassword}
            onChange={e => setConfirmPassword(e.target.value)}
            placeholder="Confirm password"
            autoComplete="new-password"
          />
          {fieldErrors.confirmPassword && <p style={{ color: 'var(--error, red)', margin: '4px 0 0' }}>{fieldErrors.confirmPassword}</p>}
        </div>

        <input
          value={firstName}
          onChange={e => setFirstName(e.target.value)}
          placeholder="First Name (optional)"
          autoComplete="given-name"
        />

        <input
          value={lastName}
          onChange={e => setLastName(e.target.value)}
          placeholder="Last Name (optional)"
          autoComplete="family-name"
        />

        <button className="btn btn-primary" type="submit" disabled={submitting}>
          {submitting ? 'Creating account...' : 'Create account'}
        </button>
      </form>

      <p style={{ marginTop: 16 }}>
        Already have an account? <Link to="/login">Log in</Link>
      </p>
    </div>
  );
}
