import { useEffect, useState } from 'react';
import { Link, useLocation } from 'react-router';
import { api } from '../api';
import { useAuth } from '../auth';
import { useShop } from '../store';
import { AccountIcon, BagIcon, CloseIcon, HeartIcon, MenuIcon, SearchIcon } from './icons';
import { Avatar } from './Avatar';

const NAV = ['Shop', 'Collections', 'Our Craft', 'Gifts', 'Journal', 'Contact'];

const anchorFor = (item: string) =>
  ({
    Shop: '/#bestsellers',
    Collections: '/#collections',
    'Our Craft': '/#craft',
    Gifts: '/#gifting',
    Journal: '/#newsletter',
    Contact: '/#footer',
  })[item] ?? '/';

function AnnouncementBar() {
  const items = ['Free shipping over $75', 'Ethically sourced cacao', 'Small-batch handcrafted'];
  return (
    <div className="bg-espresso text-ivory/85 text-[11px] tracking-[0.18em] uppercase overflow-hidden">
      <div className="hidden sm:flex justify-center gap-12 py-2.5">
        {items.map((text) => (
          <span key={text}>{text}</span>
        ))}
      </div>
      {/* single rotating message keeps the bar readable on small screens */}
      <div className="sm:hidden flex whitespace-nowrap py-2.5 animate-marquee w-max">
        {[...items, ...items].map((text, i) => (
          <span key={i} className="mx-6">
            {text}
          </span>
        ))}
      </div>
    </div>
  );
}

const linkStyle =
  'relative pb-1 transition-colors duration-300 hover:text-gold after:absolute after:bottom-0 after:left-0 after:h-px after:w-0 after:bg-gold after:transition-all after:duration-500 hover:after:w-full';

export function Header() {
  const { cartCount, wishlist, setCartOpen } = useShop();
  const { user, isAuthenticated, isAdmin, login, logout } = useAuth();
  const { pathname } = useLocation();
  const [scrolled, setScrolled] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [accountOpen, setAccountOpen] = useState(false);
  const [displayName, setDisplayName] = useState<string | null>(null);
  const [avatarUrl, setAvatarUrl] = useState<string | null>(null);

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 40);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  // Google-federated accounts can have an unfriendly `preferred_username`
  // (e.g. a numeric subject id) — displayName, when set, always wins.
  useEffect(() => {
    if (!isAuthenticated) {
      setDisplayName(null);
      setAvatarUrl(null);
      return;
    }
    api.getMyProfile()
      .then((p) => {
        setDisplayName(p.displayName);
        // Read from the profile, never from the token's `picture` claim: the
        // claim is always Google's, and it would override a customer who has
        // chosen their upload (docs/users/user-pic.md D1).
        setAvatarUrl(p.avatarUrl);
      })
      .catch(() => {});
  }, [isAuthenticated]);

  const accountLabel = displayName ?? user?.name;

  // Transparent-over-hero only makes sense on the home page.
  const solid = scrolled || menuOpen || pathname !== '/';

  return (
    <header
      className={`fixed inset-x-0 top-0 z-40 transition-all duration-700 ease-luxe ${
        solid ? 'bg-espresso/95 backdrop-blur-md shadow-lg shadow-espresso/30' : 'bg-transparent'
      }`}
    >
      <AnnouncementBar />
      <nav className="mx-auto flex max-w-7xl items-center justify-between px-5 py-4 lg:px-8 text-ivory">
        <button
          className="lg:hidden p-2 -ml-2"
          aria-label={menuOpen ? 'Close menu' : 'Open menu'}
          onClick={() => setMenuOpen((v) => !v)}
        >
          {menuOpen ? <CloseIcon /> : <MenuIcon />}
        </button>

        <Link to="/" className="font-display text-2xl tracking-wide">
          SI <span className="text-gold italic">Chocolate</span>
        </Link>

        <ul className="hidden lg:flex items-center gap-9 text-[13px] tracking-[0.14em] uppercase">
          {NAV.map((item) => (
            <li key={item}>
              <Link to={anchorFor(item)} className={linkStyle}>
                {item}
              </Link>
            </li>
          ))}
          {isAdmin && (
            <li>
              <Link to="/admin" className={`${linkStyle} text-gold`}>
                Admin
              </Link>
            </li>
          )}
        </ul>

        <div className="flex items-center gap-1 sm:gap-3">
          <button className="p-2 transition-colors hover:text-gold" aria-label="Search">
            <SearchIcon />
          </button>
          {isAuthenticated ? (
            <div className="relative">
              <button
                className="flex items-center gap-2 p-2 transition-colors hover:text-gold"
                aria-label={`Account, signed in as ${accountLabel}`}
                aria-expanded={accountOpen}
                onClick={() => setAccountOpen((v) => !v)}
              >
                {avatarUrl ? <Avatar src={avatarUrl} name={accountLabel ?? '?'} size={26} /> : <AccountIcon />}
                <span className="hidden sm:inline max-w-24 truncate text-xs tracking-[0.14em] uppercase">
                  {accountLabel}
                </span>
              </button>
              {accountOpen && (
                <div className="absolute right-0 top-full mt-2 w-44 rounded-md bg-ivory py-2 text-cocoa shadow-xl shadow-espresso/30">
                  <p className="px-4 py-1.5 text-xs text-cocoa/50">Signed in as {accountLabel}</p>
                  <Link
                    to="/profile"
                    onClick={() => setAccountOpen(false)}
                    className="block w-full px-4 py-1.5 text-left text-xs uppercase tracking-[0.14em] transition-colors hover:text-terracotta"
                  >
                    My Profile
                  </Link>
                  {isAdmin && (
                    <Link
                      to="/admin"
                      onClick={() => setAccountOpen(false)}
                      className="block w-full px-4 py-1.5 text-left text-xs uppercase tracking-[0.14em] transition-colors hover:text-terracotta"
                    >
                      Admin
                    </Link>
                  )}
                  <button
                    onClick={logout}
                    className="block w-full px-4 py-1.5 text-left text-xs uppercase tracking-[0.14em] transition-colors hover:text-terracotta"
                  >
                    Sign out
                  </button>
                </div>
              )}
            </div>
          ) : (
            <>
              <Link
                to="/register"
                className="hidden p-2 text-xs uppercase tracking-[0.14em] transition-colors hover:text-gold sm:inline-block"
              >
                Register
              </Link>
              <button
                className="flex items-center gap-2 p-2 transition-colors hover:text-gold"
                aria-label="Sign in"
                onClick={login}
              >
                <AccountIcon />
                <span className="hidden sm:inline text-xs tracking-[0.14em] uppercase">Sign in</span>
              </button>
            </>
          )}
          <button className="relative p-2 transition-colors hover:text-gold" aria-label="Wishlist">
            <HeartIcon />
            {wishlist.size > 0 && (
              <span className="absolute -top-0.5 -right-0.5 flex h-4 w-4 items-center justify-center rounded-full bg-terracotta text-[10px] font-medium">
                {wishlist.size}
              </span>
            )}
          </button>
          <button
            className="relative p-2 transition-colors hover:text-gold"
            aria-label={`Cart, ${cartCount} items`}
            onClick={() => setCartOpen(true)}
          >
            <BagIcon />
            {cartCount > 0 && (
              <span className="absolute -top-0.5 -right-0.5 flex h-4 w-4 items-center justify-center rounded-full bg-gold text-espresso text-[10px] font-medium">
                {cartCount}
              </span>
            )}
          </button>
        </div>
      </nav>

      {menuOpen && (
        <ul className="lg:hidden bg-espresso/95 backdrop-blur-md px-8 pb-8 pt-2 space-y-4 text-ivory text-sm tracking-[0.14em] uppercase">
          {NAV.map((item) => (
            <li key={item}>
              <Link to={anchorFor(item)} className="block py-1" onClick={() => setMenuOpen(false)}>
                {item}
              </Link>
            </li>
          ))}
          {isAdmin && (
            <li>
              <Link to="/admin" className="block py-1 text-gold" onClick={() => setMenuOpen(false)}>
                Admin
              </Link>
            </li>
          )}
          {!isAuthenticated && (
            <li>
              <Link to="/register" className="block py-1" onClick={() => setMenuOpen(false)}>
                Register
              </Link>
            </li>
          )}
        </ul>
      )}
    </header>
  );
}
