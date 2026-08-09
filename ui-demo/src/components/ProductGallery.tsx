import { useEffect, useMemo, useState } from 'react';
import type { MediaItem, Product } from '../types';
import { getDefaultMedia } from '../utils/media';
import { ChocolateArt, variantFor } from './ChocolateArt';

/**
 * The product's pictures, with the chosen one large and the rest as thumbnails
 * beneath — the same capability ui-shop's ProductDetail has, in this
 * storefront's own visual language.
 *
 * Falls back to the generated ChocolateArt illustration when a piece has no
 * uploaded media, which is still the normal case for the seeded catalogue and
 * for the editorial fallback pieces (negative ids, client-only, no media at
 * all). That keeps the grid looking complete and costs no image request —
 * see docs/plans/add-chocolates.md §3.
 */
export function ProductGallery({ product }: { product: Product }) {
  // Images only. MediaItem carries a contentType and uploads may be video, but
  // nothing in either front end plays video today, and an <img> pointed at one
  // renders as a broken tile — better to leave it out than to show that.
  const images = useMemo(() => {
    const all = (product.media ?? []).filter(
      (m) => !m.contentType || m.contentType.startsWith('image/'),
    );
    const preferred = getDefaultMedia(product.media);
    if (!preferred) return all;
    // Lead with the piece's chosen photo, keeping the admin's order after it.
    return [preferred, ...all.filter((m) => m.key !== preferred.key)];
  }, [product.media]);

  const [activeKey, setActiveKey] = useState<string | null>(images[0]?.key ?? null);

  // Deep-linking between products reuses this component, so the selection has
  // to follow the product rather than persist across it.
  useEffect(() => {
    setActiveKey(images[0]?.key ?? null);
  }, [product.id, images]);

  const active: MediaItem | undefined =
    images.find((m) => m.key === activeKey) ?? images[0];

  if (!active) {
    return (
      <div className="overflow-hidden rounded-lg">
        <ChocolateArt
          seed={product.id}
          variant={variantFor(product.name, product.id)}
          className="aspect-square w-full"
        />
      </div>
    );
  }

  return (
    <div>
      <div className="overflow-hidden rounded-lg bg-white/60">
        <img
          src={active.url}
          alt={product.name}
          className="aspect-square w-full object-cover"
        />
      </div>

      {images.length > 1 && (
        <ul className="mt-4 flex flex-wrap gap-3" aria-label={`More pictures of ${product.name}`}>
          {images.map((item, index) => {
            const isActive = item.key === active.key;
            return (
              <li key={item.key}>
                <button
                  type="button"
                  onClick={() => setActiveKey(item.key)}
                  aria-current={isActive}
                  aria-label={`Show picture ${index + 1} of ${images.length}`}
                  className={`h-20 w-20 overflow-hidden rounded-md border transition-colors duration-300 ${
                    isActive
                      ? 'border-gold'
                      : 'border-cocoa/15 hover:border-cocoa/40'
                  }`}
                >
                  <img
                    src={item.url}
                    alt=""
                    className={`h-full w-full object-cover transition-opacity duration-300 ${
                      isActive ? 'opacity-100' : 'opacity-70 hover:opacity-100'
                    }`}
                  />
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
