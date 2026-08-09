import { useEffect, useMemo, useState } from 'react';
import type { MediaItem, Product } from '../types';
import { getDefaultMedia, isVideoMedia } from '../utils/media';
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
  // Images and video both, since this is the one place that knows how to render
  // either. getDefaultMedia stays image-only on purpose, so the piece always
  // opens on a still rather than an unplayed video frame — and so every other
  // component, which renders a plain <img>, can never be handed a video.
  const items = useMemo(() => {
    const all = product.media ?? [];
    const preferred = getDefaultMedia(product.media);
    if (!preferred) return all;
    // Lead with the piece's chosen photo, keeping the admin's order after it.
    return [preferred, ...all.filter((m) => m.key !== preferred.key)];
  }, [product.media]);

  const [activeKey, setActiveKey] = useState<string | null>(items[0]?.key ?? null);

  // Deep-linking between products reuses this component, so the selection has
  // to follow the product rather than persist across it.
  useEffect(() => {
    setActiveKey(items[0]?.key ?? null);
  }, [product.id, items]);

  const active: MediaItem | undefined =
    items.find((m) => m.key === activeKey) ?? items[0];

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
        {isVideoMedia(active) ? (
          // key forces a fresh element per source: without it React reuses the
          // node and the previous video keeps playing under the new src.
          <video
            key={active.key}
            src={active.url}
            controls
            playsInline
            preload="metadata"
            className="aspect-square w-full bg-cocoa/5 object-cover"
          />
        ) : (
          <img
            src={active.url}
            alt={product.name}
            className="aspect-square w-full object-cover"
          />
        )}
      </div>

      {items.length > 1 && (
        <ul className="mt-4 flex flex-wrap gap-3" aria-label={`More of ${product.name}`}>
          {items.map((item, index) => {
            const isActive = item.key === active.key;
            return (
              <li key={item.key}>
                <button
                  type="button"
                  onClick={() => setActiveKey(item.key)}
                  aria-current={isActive}
                  aria-label={
                    isVideoMedia(item)
                      ? `Play video ${index + 1} of ${items.length}`
                      : `Show picture ${index + 1} of ${items.length}`
                  }
                  className={`relative block h-20 w-20 overflow-hidden rounded-md border transition-colors duration-300 ${
                    isActive
                      ? 'border-gold'
                      : 'border-cocoa/15 hover:border-cocoa/40'
                  }`}
                >
                  {isVideoMedia(item) ? (
                    <>
                      {/* metadata only: enough for the first frame, without
                          pulling the whole file for a thumbnail. */}
                      <video
                        src={item.url}
                        preload="metadata"
                        muted
                        playsInline
                        className={`h-full w-full bg-cocoa/10 object-cover transition-opacity duration-300 ${
                          isActive ? 'opacity-100' : 'opacity-70'
                        }`}
                      />
                      <span
                        aria-hidden="true"
                        className="absolute inset-0 flex items-center justify-center"
                      >
                        <span className="flex h-7 w-7 items-center justify-center rounded-full bg-cocoa/70 text-[10px] text-ivory">
                          ▶
                        </span>
                      </span>
                    </>
                  ) : (
                    <img
                      src={item.url}
                      alt=""
                      className={`h-full w-full object-cover transition-opacity duration-300 ${
                        isActive ? 'opacity-100' : 'opacity-70 hover:opacity-100'
                      }`}
                    />
                  )}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
