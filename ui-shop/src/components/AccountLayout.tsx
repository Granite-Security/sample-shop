import { Outlet } from 'react-router';
import AccountNav from './AccountNav';

// Shared shell for every account page — the .page padding/max-width that
// used to live in each page individually now lives here once, alongside a
// persistent sidebar so navigating between account pages never bounces back
// through /profile. Mirrors Catalog's .catalog-page/.category-sidebar split.
export default function AccountLayout() {
  return (
    <div className="page account-page">
      <AccountNav />
      <div className="account-content">
        <Outlet />
      </div>
    </div>
  );
}
