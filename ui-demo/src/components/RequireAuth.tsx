import { Link, Outlet } from 'react-router';
import { useAuth } from '../auth';

// Shared account-area guard, extracted from ProfilePage.tsx so
// Profile/Password/Files/Addresses all share the same loading/sign-in
// treatment instead of each re-declaring it. Unlike ui-shop, ui-demo shows a
// sign-in CTA rather than redirecting — that's the existing behavior for this
// storefront and is preserved as-is.
export default function RequireAuth() {
  const { isAuthenticated, loading, login } = useAuth();

  if (loading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-ivory pt-28 text-cocoa/50">Loading…</div>
    );
  }

  if (!isAuthenticated) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center gap-4 bg-ivory px-6 pt-28 text-center">
        <h1 className="font-display text-[32px] text-cocoa">Your Account Awaits</h1>
        <p className="max-w-md text-cocoa/60">Sign in to view and manage your profile and addresses.</p>
        <button
          onClick={login}
          className="mt-4 bg-cocoa px-8 py-3 text-xs uppercase tracking-[0.18em] text-ivory transition-colors duration-300 hover:bg-espresso"
        >
          Sign In
        </button>
        <p className="text-sm text-cocoa/60">
          New here?{' '}
          <Link to="/register" className="text-cocoa underline decoration-gold underline-offset-4 hover:text-terracotta">
            Create an account
          </Link>
        </p>
      </div>
    );
  }

  return <Outlet />;
}
