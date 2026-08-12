import { useEffect, useRef, useState } from 'react';
import { userManager } from '../oauth';

/**
 * Handles the OAuth2 redirect at /callback: exchanges the authorization code,
 * then returns to the storefront root. No router needed — App renders this
 * whenever location.pathname is /callback.
 */
export function Callback() {
  const called = useRef(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (called.current) return;
    called.current = true;

    userManager
      .signinRedirectCallback()
      // The state we asked signinRedirect to carry comes back here. The public
      // profile page uses it so "sign in to message" returns to the profile the
      // visitor was looking at instead of the storefront root
      // (docs/profile/public-profile.md step 7). Only same-origin paths are
      // honoured — state survives a round trip through the browser, so an
      // absolute URL here would be an open redirect.
      .then((user) => {
        const returnTo = (user?.state as { returnTo?: string } | undefined)?.returnTo;
        const safe = returnTo && returnTo.startsWith('/') && !returnTo.startsWith('//') ? returnTo : '/';
        window.location.replace(safe);
      })
      .catch((err) => {
        console.error('Login callback error', err);
        setError(String(err));
      });
  }, []);

  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-4 bg-espresso px-6 text-center text-ivory">
      <p className="font-display text-2xl">
        SI <span className="italic text-gold">Chocolate</span>
      </p>
      {error ? (
        <>
          <p className="text-sm text-terracotta">Login failed: {error}</p>
          <a href="/" className="text-xs uppercase tracking-[0.2em] text-gold underline underline-offset-4">
            Back to the boutique
          </a>
        </>
      ) : (
        <p className="text-sm text-ivory/60">Completing login…</p>
      )}
    </div>
  );
}
