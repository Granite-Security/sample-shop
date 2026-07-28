import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { api } from '../api';
import { useAuth } from '../auth';
import { useCart } from '../contexts/CartContext';
import Avatar from './Avatar';

export default function Header() {
  const { isAuthenticated, isAdmin, user, logout } = useAuth();
  const { itemCount } = useCart();
  const [displayName, setDisplayName] = useState<string | null>(null);
  const [avatarUrl, setAvatarUrl] = useState<string | null>(null);

  useEffect(() => {
    if (!isAuthenticated) {
      setDisplayName(null);
      setAvatarUrl(null);
      return;
    }
    api.profile.getProfile()
      .then(p => {
        setDisplayName(p.displayName);
        // Read from the profile, never from the token's `picture` claim: the
        // claim is always Google's, and it would override a user who has
        // chosen their upload (docs/users/user-pic.md D1).
        setAvatarUrl(p.avatarUrl);
      })
      .catch(() => {});
  }, [isAuthenticated]);

  return (
    <header className="header">
      <Link to="/" className="logo">Shop</Link>
      <nav className="nav">
        <Link to="/catalog">Catalog</Link>
        {isAuthenticated && <Link to="/orders">Orders</Link>}
        {isAdmin && <Link to="/admin">Admin</Link>}
      </nav>
      <div className="header-actions">
        <Link to="/cart" className="cart-link">
          Cart{itemCount > 0 && <span className="cart-badge">{itemCount}</span>}
        </Link>
        {isAuthenticated ? (
          <span className="user-info">
            <Link
              to="/profile"
              style={{ fontWeight: 600, color: 'inherit', display: 'inline-flex', alignItems: 'center', gap: 8 }}
            >
              <Avatar src={avatarUrl} name={displayName ?? user?.name ?? '?'} size={28} />
              {displayName ?? user?.name}
            </Link>
            <button onClick={logout} className="btn-link">Logout</button>
          </span>
        ) : (
          <>
            <Link to="/login" className="btn-link">Login</Link>
            <Link to="/register" className="btn-link">Register</Link>
          </>
        )}
      </div>
    </header>
  );
}
