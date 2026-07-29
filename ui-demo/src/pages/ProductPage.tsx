import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router';
import { api } from '../api';
import { formatPrice, useShop } from '../store';
import type { Product } from '../types';
import { ChocolateArt, variantFor } from './../components/ChocolateArt';
import { ArrowIcon, HeartIcon, LeafIcon, StarIcon, TruckIcon } from '../components/icons';
import { Reveal } from '../components/Reveal';
import { getDefaultMedia } from '../utils/media';

/** Same deterministic pseudo-rating used by the bestseller cards. */
const ratingFor = (id: number) => 4.6 + (Math.abs(id * 7) % 4) / 10;

export function ProductPage() {
  const { id } = useParams();
  const { products, addToCart, toggleWishlist, wishlist } = useShop();
  const [fetched, setFetched] = useState<Product | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [quantity, setQuantity] = useState(1);

  const numericId = Number(id);
  // Fallback products (negative ids) only exist client-side; everything else
  // can also be deep-linked and fetched straight from the shop service.
  const fromStore = products.find((p) => p.id === numericId) ?? null;
  const product = fromStore ?? fetched;

  useEffect(() => {
    setNotFound(false);
    setFetched(null);
    setQuantity(1);
    window.scrollTo(0, 0);
    if (!Number.isFinite(numericId) || numericId < 0) return;
    let cancelled = false;
    api
      .getProduct(numericId)
      .then((p) => {
        if (!cancelled) setFetched(p);
      })
      .catch(() => {
        if (!cancelled) setNotFound(true);
      });
    return () => {
      cancelled = true;
    };
  }, [numericId]);

  if (!product) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center gap-4 bg-ivory px-6 pt-28 text-center">
        {notFound ? (
          <>
            <h1 className="font-display text-[32px] text-cocoa">This piece has left the atelier</h1>
            <p className="max-w-md text-cocoa/60">
              The product you're looking for isn't in the collection anymore — but the current batch is.
            </p>
            <Link
              to="/shop"
              className="mt-4 border border-cocoa px-8 py-3 text-xs uppercase tracking-[0.18em] text-cocoa transition-colors duration-300 hover:bg-cocoa hover:text-ivory"
            >
              Browse the Collection
            </Link>
          </>
        ) : (
          <p className="text-cocoa/50">Loading…</p>
        )}
      </div>
    );
  }

  const rating = ratingFor(product.id);
  const wished = wishlist.has(product.id);
  const inStock = product.stock > 0;
  const defaultImage = getDefaultMedia(product.media);

  return (
    <div className="bg-ivory pt-28 lg:pt-32">
      <div className="mx-auto max-w-7xl px-5 pb-24 lg:px-8">
        <nav aria-label="Breadcrumb" className="text-xs uppercase tracking-[0.18em] text-cocoa/50">
          <Link to="/" className="transition-colors hover:text-terracotta">
            Home
          </Link>
          <span className="mx-2">/</span>
          <Link to="/shop" className="transition-colors hover:text-terracotta">
            Shop
          </Link>
          <span className="mx-2">/</span>
          <span className="text-cocoa">{product.name}</span>
        </nav>

        <div className="mt-8 grid gap-10 lg:grid-cols-2 lg:gap-20">
          <Reveal>
            <div className="overflow-hidden rounded-lg">
              {defaultImage ? (
                <img src={defaultImage.url} alt={product.name} className="aspect-square w-full object-cover" />
              ) : (
                <ChocolateArt
                  seed={product.id}
                  variant={variantFor(product.name, product.id)}
                  className="aspect-square w-full"
                />
              )}
            </div>
          </Reveal>

          <Reveal delay={150}>
            <div className="flex items-center gap-1 text-gold" aria-label={`Rated ${rating.toFixed(1)} out of 5`}>
              {[1, 2, 3, 4, 5].map((n) => (
                <StarIcon key={n} className="h-4 w-4" filled={n <= Math.round(rating)} />
              ))}
              <span className="ml-2 text-sm text-cocoa/50">{rating.toFixed(1)} — small-batch reviews</span>
            </div>

            <h1 className="mt-4 font-display text-[42px] leading-[1.1] text-cocoa lg:text-[52px]">
              {product.name}
            </h1>
            <p className="mt-3 text-2xl text-terracotta">{formatPrice(product.price)}</p>
            <p className={`mt-2 text-sm ${inStock ? 'text-sage' : 'text-terracotta'}`}>
              {inStock ? `In stock — ${product.stock} pieces from the current batch` : 'This batch is sold out'}
            </p>

            <p className="mt-6 max-w-lg text-cocoa/70">{product.description}</p>

            <div className="mt-8 flex flex-wrap items-center gap-4">
              <div className="flex items-center border border-cocoa/20 text-cocoa">
                <button
                  className="px-4 py-3"
                  aria-label="Decrease quantity"
                  onClick={() => setQuantity((q) => Math.max(1, q - 1))}
                >
                  −
                </button>
                <span className="w-8 text-center">{quantity}</span>
                <button
                  className="px-4 py-3"
                  aria-label="Increase quantity"
                  onClick={() => setQuantity((q) => q + 1)}
                >
                  +
                </button>
              </div>
              <button
                disabled={!inStock}
                onClick={() => {
                  for (let i = 0; i < quantity; i++) addToCart(product);
                }}
                className="bg-cocoa px-10 py-4 text-xs uppercase tracking-[0.2em] text-ivory transition-colors duration-300 hover:bg-espresso disabled:cursor-not-allowed disabled:opacity-40"
              >
                Add to Cart
              </button>
              <button
                aria-label={wished ? 'Remove from wishlist' : 'Add to wishlist'}
                aria-pressed={wished}
                onClick={() => toggleWishlist(product.id)}
                className={`border border-cocoa/20 p-4 transition-colors ${
                  wished ? 'text-terracotta' : 'text-cocoa/50 hover:text-terracotta'
                }`}
              >
                <HeartIcon filled={wished} />
              </button>
            </div>

            <dl className="mt-10 space-y-4 border-t border-cocoa/10 pt-8 text-sm">
              <div className="flex items-start gap-3">
                <LeafIcon className="mt-0.5 h-5 w-5 shrink-0 text-sage" />
                <div>
                  <dt className="font-medium text-cocoa">Ethically sourced</dt>
                  <dd className="text-cocoa/60">
                    Direct-trade cacao from family farms in Ecuador, Ghana and Madagascar.
                  </dd>
                </div>
              </div>
              <div className="flex items-start gap-3">
                <TruckIcon className="mt-0.5 h-5 w-5 shrink-0 text-sage" />
                <div>
                  <dt className="font-medium text-cocoa">Shipping</dt>
                  <dd className="text-cocoa/60">
                    Complimentary over $75. Cold-packed in summer so it arrives with a perfect snap.
                  </dd>
                </div>
              </div>
            </dl>
          </Reveal>
        </div>

        <RelatedProducts currentId={product.id} />
      </div>
    </div>
  );
}

function RelatedProducts({ currentId }: { currentId: number }) {
  const { products } = useShop();
  const related = products.filter((p) => p.id !== currentId).slice(0, 4);
  if (related.length === 0) return null;

  return (
    <section className="mt-24" aria-label="You may also like">
      <Reveal>
        <h2 className="font-display text-[28px] text-cocoa lg:text-[32px]">You May Also Like</h2>
      </Reveal>
      <div className="mt-8 grid grid-cols-2 gap-x-6 gap-y-10 lg:grid-cols-4">
        {related.map((p, i) => {
          const defaultImage = getDefaultMedia(p.media);
          return (
          <Reveal key={p.id} delay={i * 100}>
            <Link to={`/products/${p.id}`} className="group block">
              <div className="overflow-hidden rounded-lg">
                {defaultImage ? (
                  <img
                    src={defaultImage.url}
                    alt={p.name}
                    className="aspect-square w-full object-cover transition-transform duration-1000 ease-luxe group-hover:scale-108"
                  />
                ) : (
                  <ChocolateArt
                    seed={p.id}
                    variant={variantFor(p.name, p.id)}
                    className="aspect-square w-full transition-transform duration-1000 ease-luxe group-hover:scale-108"
                  />
                )}
              </div>
              <h3 className="mt-3 font-display text-lg text-cocoa">{p.name}</h3>
              <p className="mt-1 flex items-center justify-between text-sm">
                <span className="text-terracotta">{formatPrice(p.price)}</span>
                <span className="flex items-center gap-1 text-xs uppercase tracking-[0.14em] text-cocoa/50 transition-colors group-hover:text-terracotta">
                  View <ArrowIcon className="h-3 w-3" />
                </span>
              </p>
            </Link>
          </Reveal>
          );
        })}
      </div>
    </section>
  );
}
