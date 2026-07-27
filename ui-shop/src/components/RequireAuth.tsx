import { Navigate, Outlet, useLocation } from 'react-router';
import { useAuth } from '../auth';

// Shared account-area guard — extracted so Profile/Password/Files/Addresses
// don't each roll their own copy of the isAuthenticated check that used to
// live only in Profile.tsx.
export default function RequireAuth() {
  const { isAuthenticated, loading } = useAuth();
  const location = useLocation();

  if (loading) {
    return <div className="page"><div className="spinner" style={{ margin: '0 auto' }} /></div>;
  }
  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }
  return <Outlet />;
}
