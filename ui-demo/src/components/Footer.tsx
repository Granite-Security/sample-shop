import { InstagramIcon } from './icons';

const COLUMNS: Record<string, string[]> = {
  Shop: ['Dark Chocolate', 'Truffles', 'Gift Boxes', 'Drinking Chocolate'],
  About: ['Our Story', 'The Atelier', 'Journal', 'Careers'],
  Sustainability: ['Direct Trade', 'Our Farms', 'Packaging', 'Impact Report'],
  Support: ['FAQ', 'Shipping', 'Returns', 'Contact'],
};

export function Footer() {
  return (
    <footer id="footer" className="bg-espresso text-ivory">
      <div className="mx-auto max-w-7xl px-5 py-16 lg:px-8 lg:py-20">
        <div className="grid gap-10 md:grid-cols-[2fr_repeat(4,1fr)]">
          <div>
            <p className="font-display text-2xl">
              SI <span className="italic text-gold">Chocolate</span>
            </p>
            <p className="mt-4 max-w-xs text-sm text-ivory/55">
              Small-batch chocolate crafted from ethically sourced cacao — traditional craftsmanship,
              modern refinement.
            </p>
            <div className="mt-6 flex gap-4 text-ivory/60">
              <a href="#top" aria-label="Instagram" className="transition-colors hover:text-gold">
                <InstagramIcon />
              </a>
            </div>
          </div>

          {Object.entries(COLUMNS).map(([heading, links]) => (
            <nav key={heading} aria-label={heading}>
              <h3 className="text-xs uppercase tracking-[0.25em] text-gold">{heading}</h3>
              <ul className="mt-4 space-y-2.5 text-sm text-ivory/60">
                {links.map((link) => (
                  <li key={link}>
                    <a href="#top" className="transition-colors hover:text-ivory">
                      {link}
                    </a>
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
