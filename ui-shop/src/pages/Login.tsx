import { useEffect } from 'react';
import { useAuth } from '../auth';
import { useNavigate } from 'react-router';
import { userManager } from '../oauth';

export default function Login() {
  const { isAuthenticated, loading } = useAuth();
  const navigate = useNavigate();

  useEffect(() => {
    if (loading) return;
    if (isAuthenticated) {
      navigate('/', { replace: true });
    } else {
      userManager.signinRedirect();
    }
  }, [isAuthenticated, loading, navigate]);

  return <div className="page"><p>Redirecting to login...</p></div>;
}
