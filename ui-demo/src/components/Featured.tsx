import { Link } from 'react-router';
import { useShop } from '../store';
import { ArrowIcon } from './icons';
import { ProductCard, ProductGridSkeleton } from './ProductCard';
import { Reveal } from './Reveal';

/**
 * A short, curated taste of the catalog on the home page — the full grid
 * lives on /shop. Four pieces, deliberately: the home page is an invitation,
 * not the shop itself.
 */
export function Featured() {
  const { products, loading } = useShop();
  const shown = products.slice(0, 4);

  return (
    <section className="bg-ivory">
      <div className="mx-auto max-w-7xl px-5 py-24 lg:px-8 lg:py-32">
        <Reveal>
          <div className="flex flex-wrap items-end justify-between gap-4">
            <div>
              <p className="text-xs uppercase tracking-[0.3em] text-terracotta">Most Loved</p>
              <h2 className="mt-4 font-display text-[32px] leading-tight text-cocoa lg:text-[48px]">
                A Few Favourites
              </h2>
            </div>
            <Link
              to="/shop"
              className="inline-flex items-center gap-2 text-xs uppercase tracking-[0.18em] text-cocoa underline decoration-gold underline-offset-8 transition-colors hover:text-terracotta"
            >
              View the whole shop <ArrowIcon />
            </Link>
          </div>
        </Reveal>

        <div className="mt-14">
          {loading ? (
            <ProductGridSkeleton count={4} />
          ) : (
            <div className="grid grid-cols-2 gap-x-6 gap-y-12 lg:grid-cols-4">
              {shown.map((product, i) => (
                <ProductCard key={product.id} product={product} index={i} />
              ))}
            </div>
          )}
        </div>
      </div>
    </section>
  );
}
