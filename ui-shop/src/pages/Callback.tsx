import { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router';
import { userManager } from '../oauth';

export default function Callback() {
  const navigate = useNavigate();
  const called = useRef(false);

  useEffect(() => {
    if (called.current) return;
    called.current = true;

    userManager.signinRedirectCallback()
      // The state we asked signinRedirect to carry comes back here. The public
      // profile page uses it so "sign in to message" returns to the profile the
      // visitor was looking at instead of the home page
      // (docs/profile/public-profile.md step 7). Only same-origin paths are
      // honoured — state survives a round trip through the browser, so an
      // absolute URL here would be an open redirect.
      .then(user => {
        const returnTo = (user?.state as { returnTo?: string } | undefined)?.returnTo;
        const safe = returnTo && returnTo.startsWith('/') && !returnTo.startsWith('//')
          ? returnTo
          : '/';
        navigate(safe, { replace: true });
      })
      .catch(err => console.error('Login callback error', err));
  }, [navigate]);

  return <div className="page"><p>Completing login...</p></div>;
}
