import { ChocolateArt } from './ChocolateArt';
import { ArrowIcon } from './icons';
import { Reveal } from './Reveal';

const COLLECTIONS = [
  {
    name: 'Dark Chocolate',
    tagline: 'Single-origin bars, 65–90%',
    seed: 2,
    variant: 'bar' as const,
  },
  {
    name: 'Signature Truffles',
    tagline: 'Hand-rolled, slow-set ganache',
    seed: 1,
    variant: 'truffle' as const,
  },
  {
    name: 'Gift Collections',
    tagline: 'Keepsake boxes, tied by hand',
    seed: 3,
    variant: 'gift' as const,
  },
];

export function Collections() {
  return (
    <section id="collections" className="mx-auto max-w-7xl px-5 py-24 lg:px-8 lg:py-32">
      <Reveal>
        <p className="text-center text-xs uppercase tracking-[0.3em] text-terracotta">The Collections</p>
        <h2 className="mt-4 text-center font-display text-[32px] leading-tight text-cocoa lg:text-[48px]">
          Three Ways to Begin
        </h2>
      </Reveal>

      <div className="mt-14 grid gap-6 md:grid-cols-3">
        {COLLECTIONS.map((collection, i) => (
          <Reveal key={collection.name} delay={i * 150}>
            <a
              href="#bestsellers"
              className="group relative block aspect-[3/4] overflow-hidden rounded-lg"
            >
              <ChocolateArt
                seed={collection.seed}
                variant={collection.variant}
                className="h-full w-full transition-transform duration-1000 ease-luxe group-hover:scale-108"
              />
              <div className="absolute inset-0 bg-gradient-to-t from-espresso/90 via-espresso/20 to-transparent" />
              <div className="absolute inset-x-0 bottom-0 p-8">
                <h3 className="font-display text-[22px] text-ivory lg:text-[28px]">{collection.name}</h3>
                <p className="mt-1 text-sm text-ivory/65">{collection.tagline}</p>
                <span className="mt-4 inline-flex items-center gap-2 text-xs uppercase tracking-[0.18em] text-gold opacity-0 transition-all duration-500 ease-luxe translate-y-2 group-hover:translate-y-0 group-hover:opacity-100">
                  Explore <ArrowIcon />
                </span>
              </div>
            </a>
          </Reveal>
        ))}
      </div>
    </section>
  );
}
