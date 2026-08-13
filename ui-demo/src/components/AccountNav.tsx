import { NavLink } from 'react-router';
import { useAuth } from '../auth';

const LINKS = [
  { to: '/profile', label: 'Profile', end: true },
  { to: '/profile/orders', label: 'Orders', end: false },
  { to: '/profile/balance', label: 'Balance', end: false },
  { to: '/profile/messages', label: 'Messages', end: false },
  { to: '/profile/password', label: 'Password', end: false },
  { to: '/profile/files', label: 'Files', end: false },
  { to: '/profile/addresses', label: 'Addresses', end: false },
];

export function AccountNav() {
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
    <nav className="w-full shrink-0 lg:w-48">
      <ul className="flex flex-row gap-2 overflow-x-auto lg:flex-col lg:gap-1 lg:overflow-visible">
        {links.map((link) => (
          <li key={link.to} className="shrink-0 lg:shrink">
            <NavLink
              to={link.to}
              end={link.end}
              className={({ isActive }) =>
                `block whitespace-nowrap px-4 py-2.5 text-xs uppercase tracking-[0.14em] transition-colors duration-300 ${
                  isActive ? 'bg-cocoa text-ivory' : 'text-cocoa hover:bg-cocoa/10'
                }`
              }
            >
              {link.label}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  );
}
