import { useEffect } from 'react';
import { useAuth } from '../auth';
import { useNavigate } from 'react-router';

export default function Login() {
  const { isAuthenticated, loading } = useAuth();
  const navigate = useNavigate();

  useEffect(() => {
    if (loading) return;
    if (isAuthenticated) {
      navigate('/', { replace: true });
    } else {
      window.location.href = 'http://localhost:8080/oauth2/authorization/oidc-client';
    }
  }, [isAuthenticated, loading, navigate]);

  return <div className="page"><p>Redirecting to login...</p></div>;
}
