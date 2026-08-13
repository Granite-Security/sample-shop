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
  const { isAdmin, isManager } = useAuth();
  // Treasury shows every user's balance, so it is admin-only. The server
  // enforces that too — this only decides what is worth rendering.
  // Vouchers is the one back-office screen a MANAGER can reach, which is why it
  // is here and not only on the admin panel: that panel is admin-gated, so a
  // link inside it is invisible to exactly the people this rule was widened for.
  // ADMIN + MANAGER matches ShopSec (docs/finance/vouchers.md §8.3) — the server
  // enforces it; this only decides what is worth rendering.
  const withVouchers = isAdmin || isManager
    ? [...LINKS, { to: '/admin/vouchers', label: 'Vouchers', end: false }]
    : LINKS;
  const links = isAdmin
    ? [...withVouchers,
       { to: '/profile/treasury', label: 'Treasury', end: false },
       { to: '/profile/revenues', label: 'Revenues', end: false }]
    : withVouchers;

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
