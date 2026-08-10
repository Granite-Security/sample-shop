import { NavLink } from 'react-router';
import { useAuth } from '../auth';

const LINKS = [
  { to: '/profile', label: 'Profile', end: true },
  { to: '/profile/password', label: 'Password', end: false },
  { to: '/profile/files', label: 'Files', end: false },
  { to: '/profile/balance', label: 'Balance', end: false },
  { to: '/messages', label: 'Messages', end: false },
  { to: '/addresses', label: 'Addresses', end: false },
  { to: '/orders', label: 'Orders', end: false },
];

export default function AccountNav() {
  const { isAdmin } = useAuth();
  // Treasury shows every user's balance, so it is admin-only. The server
  // enforces that too — this only decides what is worth rendering.
  const links = isAdmin
    ? [...LINKS,
       { to: '/profile/treasury', label: 'Treasury', end: false },
       { to: '/profile/revenues', label: 'Revenues', end: false }]
    : LINKS;

  return (
    <div className="account-nav">
      <h3>My Account</h3>
      <ul>
        {links.map(link => (
          <li key={link.to}>
            <NavLink to={link.to} end={link.end} className={({ isActive }) => (isActive ? 'active' : '')}>
              {link.label}
            </NavLink>
          </li>
        ))}
      </ul>
    </div>
  );
}
