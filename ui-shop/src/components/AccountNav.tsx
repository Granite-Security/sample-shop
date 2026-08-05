import { NavLink } from 'react-router';

const LINKS = [
  { to: '/profile', label: 'Profile', end: true },
  { to: '/profile/password', label: 'Password', end: false },
  { to: '/profile/files', label: 'Files', end: false },
  { to: '/messages', label: 'Messages', end: false },
  { to: '/addresses', label: 'Addresses', end: false },
  { to: '/orders', label: 'Orders', end: false },
];

export default function AccountNav() {
  return (
    <div className="account-nav">
      <h3>My Account</h3>
      <ul>
        {LINKS.map(link => (
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
