import { Link } from 'react-router';
import { InstagramIcon } from './icons';

// Only real destinations — every link here goes somewhere that exists.
const COLUMNS: Record<string, { label: string; to: string }[]> = {
  Shop: [
    { label: 'All Chocolate', to: '/shop' },
    { label: 'Gifts', to: '/gifts' },
  ],
  About: [
    { label: 'Our Story', to: '/our-story' },
    { label: 'The Atelier', to: '/our-story' },
  ],
  Support: [
    { label: 'Contact', to: '/contact' },
    { label: 'My Orders', to: '/profile/orders' },
  ],
};

export function Footer() {
  return (
    <footer id="footer" className="bg-espresso text-ivory">
      <div className="mx-auto max-w-7xl px-5 py-16 lg:px-8 lg:py-20">
        <div className="grid gap-10 md:grid-cols-[2fr_repeat(3,1fr)]">
          <div>
            <p className="font-display text-2xl">
              SI <span className="italic text-gold">Chocolate</span>
            </p>
            <p className="mt-4 max-w-xs text-sm text-ivory/55">
              Small-batch chocolate crafted from ethically sourced cacao — made in Cahul, Moldova.
            </p>
            <div className="mt-6 flex gap-4 text-ivory/60">
              <a
                href="https://instagram.com"
                aria-label="Instagram"
                className="transition-colors hover:text-gold"
              >
                <InstagramIcon />
              </a>
            </div>
          </div>

          {Object.entries(COLUMNS).map(([heading, links]) => (
            <nav key={heading} aria-label={heading}>
              <h3 className="text-xs uppercase tracking-[0.25em] text-gold">{heading}</h3>
              <ul className="mt-4 space-y-2.5 text-sm text-ivory/60">
                {links.map((link) => (
                  <li key={link.label}>
                    <Link to={link.to} className="transition-colors hover:text-ivory">
                      {link.label}
                    </Link>
                  </li>
                ))}
              </ul>
            </nav>
          ))}
        </div>

        <div className="mt-14 flex flex-col items-center justify-between gap-4 border-t border-ivory/10 pt-8 text-xs text-ivory/40 sm:flex-row">
          <p>© {new Date().getFullYear()} SI Chocolate. All rights reserved.</p>
          <p>Crafted in small batches. Savored slowly.</p>
        </div>
      </div>
    </footer>
  );
}
