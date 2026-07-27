import { Link } from 'react-router';
import { useAuth } from '../auth';
import { useCart } from '../contexts/CartContext';

export default function Header() {
  const { isAuthenticated, isAdmin, user, logout } = useAuth();
  const { itemCount } = useCart();

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
            <Link to="/profile" style={{ fontWeight: 600, color: 'inherit' }}>{user?.name}</Link>
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
