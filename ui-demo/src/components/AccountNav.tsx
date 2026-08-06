import { NavLink } from 'react-router';

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
  return (
    <nav className="w-full shrink-0 lg:w-48">
      <ul className="flex flex-row gap-2 overflow-x-auto lg:flex-col lg:gap-1 lg:overflow-visible">
        {LINKS.map((link) => (
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
