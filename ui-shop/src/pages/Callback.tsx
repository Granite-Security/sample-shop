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
      .then(() => navigate('/', { replace: true }))
      .catch(err => console.error('Login callback error', err));
  }, [navigate]);

  return <div className="page"><p>Completing login...</p></div>;
}
