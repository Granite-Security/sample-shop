import { useState } from 'react';
import { formatPrice, useShop } from '../store';
import { ChocolateArt, variantFor } from './ChocolateArt';
import { Reveal } from './Reveal';

/** Positions (in %) for the animated product hotspots layered over the hero art. */
const HOTSPOTS = [
  { top: '38%', left: '22%' },
  { top: '62%', left: '58%' },
  { top: '30%', left: '74%' },
];

export function Hero() {
  const { products, addToCart } = useShop();
  const [active, setActive] = useState<number | null>(null);
  const featured = products.slice(0, HOTSPOTS.length);

  return (
    <section id="top" className="relative flex min-h-screen items-center overflow-hidden bg-espresso">
      {/* cinematic background — layered gradients standing in for photography */}
      <div className="absolute inset-0 animate-drift">
        <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_30%_20%,#46281A_0%,#1E1612_60%,#120D0A_100%)]" />
        <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_75%_80%,rgba(199,165,107,0.14)_0%,transparent_50%)]" />
        <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_60%_40%,rgba(165,106,82,0.12)_0%,transparent_45%)]" />
      </div>
      {/* melted-chocolate ribbon */}
      <svg
        className="absolute inset-x-0 bottom-0 h-[45%] w-full opacity-70"
        viewBox="0 0 1440 400"
        preserveAspectRatio="none"
        aria-hidden="true"
      >
        <defs>
          <linearGradient id="hero-ribbon" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#5A3A26" />
            <stop offset="100%" stopColor="#1E1612" />
          </linearGradient>
        </defs>
        <path
          d="M0 180 C 240 80, 420 260, 720 170 C 1020 80, 1200 240, 1440 140 L 1440 400 L 0 400 Z"
          fill="url(#hero-ribbon)"
        />
        <path
          d="M0 260 C 300 180, 520 320, 820 240 C 1120 160, 1280 300, 1440 230 L 1440 400 L 0 400 Z"
          fill="#1E1612"
          opacity="0.85"
        />
      </svg>
      {/* readability overlay */}
      <div className="absolute inset-0 bg-gradient-to-b from-espresso/60 via-transparent to-espresso/80" />

      <div className="relative z-10 mx-auto w-full max-w-7xl px-5 pt-32 pb-24 lg:px-8">
        <div className="max-w-2xl">
          <Reveal>
            <p className="mb-6 text-xs uppercase tracking-[0.3em] text-gold">Small-batch · Bean to bar</p>
          </Reveal>
          <Reveal delay={150}>
            <h1 className="font-display text-[42px] leading-[1.08] text-ivory lg:text-[64px]">
              Chocolate Crafted as an <em className="text-gold not-italic font-medium italic">Experience</em>
            </h1>
          </Reveal>
          <Reveal delay={300}>
            <p className="mt-6 max-w-lg text-lg text-ivory/75">
              Ethically sourced, artisan made, and designed to be savored.
            </p>
          </Reveal>
          <Reveal delay={450}>
            <div className="mt-10 flex flex-wrap gap-4">
              <a
                href="#bestsellers"
                className="bg-gold px-9 py-4 text-xs uppercase tracking-[0.2em] text-espresso transition-all duration-500 ease-luxe hover:bg-ivory hover:shadow-xl hover:shadow-gold/20"
              >
                Shop Collection
              </a>
              <a
                href="#craft"
                className="border border-ivory/40 px-9 py-4 text-xs uppercase tracking-[0.2em] text-ivory transition-all duration-500 ease-luxe hover:border-gold hover:text-gold"
              >
                Our Story
              </a>
            </div>
          </Reveal>
        </div>
      </div>

      {/* interactive hotspots over the hero composition (desktop and up) */}
      <div className="absolute inset-y-0 right-0 z-10 hidden w-1/2 lg:block" aria-label="Featured products">
        {featured.map((product, i) => (
          <div key={product.id} className="absolute" style={{ top: HOTSPOTS[i].top, left: HOTSPOTS[i].left }}>
            <button
              aria-label={`Preview ${product.name}`}
              aria-expanded={active === i}
              onMouseEnter={() => setActive(i)}
              onFocus={() => setActive(i)}
              onClick={() => setActive(active === i ? null : i)}
              className="relative flex h-6 w-6 items-center justify-center"
            >
              <span className="absolute inset-0 rounded-full bg-gold/60 animate-pulse-ring" />
              <span className="relative h-2.5 w-2.5 rounded-full bg-gold ring-4 ring-gold/20 transition-transform duration-300 hover:scale-125" />
            </button>

            {active === i && (
              <div
                className="absolute left-1/2 top-8 z-20 w-64 -translate-x-1/2 overflow-hidden rounded-lg bg-ivory shadow-2xl shadow-espresso/50"
                onMouseLeave={() => setActive(null)}
              >
                <div className="h-32 w-full overflow-hidden">
                  <ChocolateArt
                    seed={product.id}
                    variant={variantFor(product.name, product.id)}
                    className="h-full w-full"
                  />
                </div>
                <div className="p-4">
                  <p className="font-display text-cocoa leading-snug">{product.name}</p>
                  <div className="mt-2 flex items-center justify-between">
                    <span className="text-sm text-terracotta">{formatPrice(product.price)}</span>
                    <button
                      onClick={() => addToCart(product)}
                      className="bg-cocoa px-4 py-2 text-[10px] uppercase tracking-[0.16em] text-ivory transition-colors hover:bg-espresso"
                    >
                      Quick Add
                    </button>
                  </div>
                </div>
              </div>
            )}
          </div>
        ))}
      </div>

      {/* scroll cue */}
      <div className="absolute bottom-8 left-1/2 z-10 -translate-x-1/2 text-ivory/50">
        <div className="mx-auto h-10 w-px bg-gradient-to-b from-transparent via-ivory/50 to-transparent" />
        <p className="mt-2 text-[10px] uppercase tracking-[0.3em]">Scroll</p>
      </div>
    </section>
  );
}
