import { Link } from 'react-router';
import { formatPrice, useShop } from '../store';
import type { Product } from '../types';
import { ChocolateArt, variantFor } from './ChocolateArt';
import { HeartIcon, StarIcon } from './icons';
import { Reveal } from './Reveal';
import { getDefaultMedia } from '../utils/media';

/** Deterministic pseudo-rating so cards look editorial without backend review data. */
const ratingFor = (id: number) => 4.6 + (Math.abs(id * 7) % 4) / 10;

const badgeFor = (product: Product): string | null => {
  if (product.stock > 0 && product.stock <= 50) return 'Small Batch';
  if (Math.abs(product.id) % 3 === 0) return 'Bestseller';
  if (Math.abs(product.id) % 4 === 1) return 'New';
  return null;
};

function ProductCard({ product, index }: { product: Product; index: number }) {
  const { addToCart, toggleWishlist, wishlist } = useShop();
  const wished = wishlist.has(product.id);
  const badge = badgeFor(product);
  const rating = ratingFor(product.id);
  const variant = variantFor(product.name, product.id);
  // hover swaps to an alternate composition of the same product
  const altSeed = product.id + 2;
  const defaultImage = getDefaultMedia(product.media);

  return (
    <Reveal delay={(index % 4) * 100}>
      <article className="group flex h-full flex-col">
        <div className="relative aspect-square overflow-hidden rounded-lg bg-espresso transition-all duration-700 ease-luxe group-hover:-translate-y-1.5 group-hover:shadow-xl group-hover:shadow-cocoa/20">
          <Link to={`/products/${product.id}`} aria-label={`View ${product.name}`} className="absolute inset-0">
            {defaultImage ? (
              <img
                src={defaultImage.url}
                alt={product.name}
                className="absolute inset-0 h-full w-full object-cover"
              />
            ) : (
              <>
                <ChocolateArt
                  seed={product.id}
                  variant={variant}
                  className="absolute inset-0 h-full w-full transition-opacity duration-700 group-hover:opacity-0"
                />
                <ChocolateArt
                  seed={altSeed}
                  variant={variant}
                  className="absolute inset-0 h-full w-full opacity-0 transition-opacity duration-700 group-hover:opacity-100"
                />
              </>
            )}
          </Link>

          {badge && (
            <span className="pointer-events-none absolute left-4 top-4 bg-ivory/90 px-3 py-1 text-[10px] uppercase tracking-[0.16em] text-cocoa">
              {badge}
            </span>
          )}

          <button
            aria-label={wished ? `Remove ${product.name} from wishlist` : `Add ${product.name} to wishlist`}
            aria-pressed={wished}
            onClick={() => toggleWishlist(product.id)}
            className={`absolute right-4 top-4 rounded-full bg-ivory/90 p-2 transition-all duration-300 hover:scale-110 ${
              wished ? 'text-terracotta' : 'text-cocoa/60'
            }`}
          >
            <HeartIcon className="h-4 w-4" filled={wished} />
          </button>

          <button
            onClick={() => addToCart(product)}
            className="absolute inset-x-4 bottom-4 translate-y-3 bg-ivory py-3 text-[11px] uppercase tracking-[0.18em] text-cocoa opacity-0 transition-all duration-500 ease-luxe hover:bg-gold group-hover:translate-y-0 group-hover:opacity-100"
          >
            Quick Add
          </button>
        </div>

        <div className="flex flex-1 flex-col pt-5">
          <div className="flex items-center gap-1 text-gold" aria-label={`Rated ${rating.toFixed(1)} out of 5`}>
            {[1, 2, 3, 4, 5].map((n) => (
              <StarIcon key={n} className="h-3.5 w-3.5" filled={n <= Math.round(rating)} />
            ))}
            <span className="ml-1 text-xs text-cocoa/50">{rating.toFixed(1)}</span>
          </div>
          <h3 className="mt-2 font-display text-xl text-cocoa">
            <Link to={`/products/${product.id}`} className="transition-colors hover:text-terracotta">
              {product.name}
            </Link>
          </h3>
          <p className="mt-1 line-clamp-2 text-sm text-cocoa/60">{product.description}</p>
          <div className="mt-auto flex items-center justify-between pt-4">
            <span className="text-terracotta">{formatPrice(product.price)}</span>
            <button
              onClick={() => addToCart(product)}
              className="text-[11px] uppercase tracking-[0.16em] text-cocoa underline decoration-gold underline-offset-4 transition-colors hover:text-terracotta"
            >
              Add to Cart
            </button>
          </div>
        </div>
      </article>
    </Reveal>
  );
}

export function Bestsellers() {
  const { products, loading } = useShop();
  const shown = products.slice(0, 8);

  return (
    <section id="bestsellers" className="bg-ivory">
      <div className="mx-auto max-w-7xl px-5 py-24 lg:px-8 lg:py-32">
        <Reveal>
          <div className="flex flex-wrap items-end justify-between gap-4">
            <div>
              <p className="text-xs uppercase tracking-[0.3em] text-terracotta">Most Loved</p>
              <h2 className="mt-4 font-display text-[32px] leading-tight text-cocoa lg:text-[48px]">
                The Bestsellers
              </h2>
            </div>
            <p className="max-w-sm text-sm text-cocoa/60">
              Each batch is conched for seventy-two hours and finished by hand. When a batch is gone,
              it's gone until the next one.
            </p>
          </div>
        </Reveal>

        {loading ? (
          <div className="mt-14 grid grid-cols-2 gap-x-6 gap-y-12 lg:grid-cols-4" aria-label="Loading products">
            {[...Array(8)].map((_, i) => (
              <div key={i} className="aspect-square animate-pulse rounded-lg bg-cocoa/10" />
            ))}
          </div>
        ) : (
          <div className="mt-14 grid grid-cols-2 gap-x-6 gap-y-12 lg:grid-cols-4">
            {shown.map((product, i) => (
              <ProductCard key={product.id} product={product} index={i} />
            ))}
          </div>
        )}
      </div>
    </section>
  );
}
