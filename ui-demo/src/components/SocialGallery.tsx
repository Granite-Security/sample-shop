import { Link } from 'react-router';
import { useShop } from '../store';
import { ChocolateArt, variantFor } from './ChocolateArt';
import { HeartIcon, InstagramIcon } from './icons';
import { Reveal } from './Reveal';

/** Masonry-style social wall built from the catalog's own artwork. */
export function SocialGallery() {
  const { products } = useShop();
  const tiles = products.slice(0, 6);
  const heights = ['aspect-square', 'aspect-[3/4]', 'aspect-[4/5]', 'aspect-square', 'aspect-[3/4]', 'aspect-square'];

  return (
    <section className="bg-ivory">
      <div className="mx-auto max-w-7xl px-5 py-24 lg:px-8 lg:py-32">
        <Reveal>
          <div className="flex items-center justify-center gap-3 text-terracotta">
            <InstagramIcon />
            <p className="text-xs uppercase tracking-[0.3em]">@si.chocolate</p>
          </div>
          <h2 className="mt-4 text-center font-display text-[32px] leading-tight text-cocoa lg:text-[48px]">
            From Our Atelier
          </h2>
        </Reveal>

        <div className="mt-14 columns-2 gap-4 md:columns-3 [&>*]:mb-4">
          {tiles.map((product, i) => (
            <Reveal key={product.id} delay={(i % 3) * 120}>
              <Link to={`/products/${product.id}`} className={`group relative block overflow-hidden rounded-lg ${heights[i]}`}>
                <ChocolateArt
                  seed={product.id + 11}
                  variant={variantFor(product.name, product.id)}
                  className="h-full w-full transition-transform duration-1000 ease-luxe group-hover:scale-108"
                />
                <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 bg-espresso/60 opacity-0 transition-opacity duration-500 group-hover:opacity-100">
                  <span className="flex items-center gap-1.5 text-ivory">
                    <HeartIcon className="h-4 w-4" filled />
                    <span className="text-sm">{(Math.abs(product.id * 137) % 900) + 240}</span>
                  </span>
                  <span className="px-4 text-center text-xs uppercase tracking-[0.15em] text-gold">
                    {product.name}
                  </span>
                </div>
              </Link>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  );
}
