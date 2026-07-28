import { Outlet } from 'react-router';
import { AccountNav } from './AccountNav';

// Shared shell for every account page — the bg-ivory/max-width wrapper that
// used to live in each page individually now lives here once, alongside a
// persistent sidebar so navigating between account pages never bounces back
// through /profile.
export function AccountLayout() {
  return (
    <div className="bg-ivory pt-28 lg:pt-32">
      <div className="mx-auto max-w-5xl px-5 pb-24 lg:px-8">
        <div className="flex flex-col gap-8 lg:flex-row lg:gap-14">
          <AccountNav />
          <div className="min-w-0 flex-1">
            <Outlet />
          </div>
        </div>
      </div>
    </div>
  );
}
