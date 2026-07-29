import { useMemo, useState } from 'react';
import { useShop } from '../store';
import { ProductCard, ProductGridSkeleton } from '../components/ProductCard';
import { Reveal } from '../components/Reveal';
import { SearchIcon } from '../components/icons';

type Sort = 'featured' | 'price-asc' | 'price-desc' | 'name';

const SORTS: { value: Sort; label: string }[] = [
  { value: 'featured', label: 'Featured' },
  { value: 'price-asc', label: 'Price · Low to High' },
  { value: 'price-desc', label: 'Price · High to Low' },
  { value: 'name', label: 'Alphabetical' },
];

export function ShopPage() {
  const { products, loading } = useShop();
  const [query, setQuery] = useState('');
  const [sort, setSort] = useState<Sort>('featured');

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    const matched = q
      ? products.filter(
          (p) => p.name.toLowerCase().includes(q) || p.description.toLowerCase().includes(q),
        )
      : products;
    const sorted = [...matched];
    if (sort === 'price-asc') sorted.sort((a, b) => a.price - b.price);
    if (sort === 'price-desc') sorted.sort((a, b) => b.price - a.price);
    if (sort === 'name') sorted.sort((a, b) => a.name.localeCompare(b.name));
    return sorted;
  }, [products, query, sort]);

  return (
    <>
      {/* Compact banner rather than a full hero — the products are the point. */}
      <section className="relative overflow-hidden bg-espresso pt-32 pb-16 lg:pt-40 lg:pb-20">
        <div className="absolute inset-0">
          <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_25%_20%,#46281A_0%,#1E1612_65%,#120D0A_100%)]" />
          <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_80%_90%,rgba(199,165,107,0.12)_0%,transparent_55%)]" />
        </div>
        <div className="relative mx-auto max-w-7xl px-5 text-center lg:px-8">
          <Reveal>
            <p className="text-xs uppercase tracking-[0.3em] text-gold">The Shop</p>
            <h1 className="mt-4 font-display text-[36px] leading-tight text-ivory lg:text-[56px]">
              Every Piece We Make
            </h1>
            <p className="mx-auto mt-5 max-w-xl text-ivory/70">
              Bars, truffles and gift boxes, made in batches small enough to finish by hand.
            </p>
          </Reveal>
        </div>
      </section>

      <section className="bg-ivory">
        <div className="mx-auto max-w-7xl px-5 py-16 lg:px-8 lg:py-24">
          <Reveal>
            <div className="flex flex-col gap-5 border-b border-cocoa/10 pb-8 sm:flex-row sm:items-center sm:justify-between">
              <div className="relative w-full sm:max-w-xs">
                <label htmlFor="shop-search" className="sr-only">
                  Search the shop
                </label>
                <SearchIcon className="pointer-events-none absolute left-0 top-1/2 h-4 w-4 -translate-y-1/2 text-cocoa/40" />
                <input
                  id="shop-search"
                  type="search"
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  placeholder="Search"
                  className="w-full border-b border-cocoa/20 bg-transparent py-2 pl-7 text-sm text-cocoa placeholder:text-cocoa/40 focus:border-gold focus:outline-none"
                />
              </div>

              <div className="flex items-center gap-4">
                <span className="text-xs uppercase tracking-[0.16em] text-cocoa/45">
                  {shown.length} {shown.length === 1 ? 'piece' : 'pieces'}
                </span>
                <label htmlFor="shop-sort" className="sr-only">
                  Sort products
                </label>
                <select
                  id="shop-sort"
                  value={sort}
                  onChange={(e) => setSort(e.target.value as Sort)}
                  className="border-b border-cocoa/20 bg-transparent py-2 text-xs uppercase tracking-[0.16em] text-cocoa focus:border-gold focus:outline-none"
                >
                  {SORTS.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </div>
            </div>
          </Reveal>

          <div className="mt-14">
            {loading ? (
              <ProductGridSkeleton />
            ) : shown.length === 0 ? (
              <Reveal>
                <div className="py-20 text-center">
                  <p className="font-display text-2xl text-cocoa">Nothing matches “{query}”.</p>
                  <button
                    onClick={() => setQuery('')}
                    className="mt-4 text-xs uppercase tracking-[0.18em] text-terracotta underline decoration-gold underline-offset-8"
                  >
                    Show everything
                  </button>
                </div>
              </Reveal>
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
    </>
  );
}
