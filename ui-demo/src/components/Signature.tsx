import { Link } from 'react-router';
import { formatPrice, useShop } from '../store';
import { ChocolateArt, variantFor } from './ChocolateArt';
import { Reveal } from './Reveal';

/** Magazine-style asymmetric showcase of the premium pieces. */
export function Signature() {
  const { products } = useShop();
  // most expensive pieces read as the "signature" tier
  const premium = [...products].sort((a, b) => b.price - a.price).slice(0, 3);
  if (premium.length < 3) return null;
  const [heroPiece, second, third] = premium;

  return (
    <section className="overflow-hidden bg-ivory">
      <div className="mx-auto max-w-7xl px-5 py-24 lg:px-8 lg:py-32">
        <Reveal>
          <p className="text-xs uppercase tracking-[0.3em] text-terracotta">The Signature Experience</p>
          <h2 className="mt-4 max-w-xl font-display text-[32px] leading-tight text-cocoa lg:text-[48px]">
            Pieces Worth Slowing Down For
          </h2>
        </Reveal>

        <div className="mt-14 grid gap-6 lg:grid-cols-12 lg:grid-rows-2">
          <Reveal className="lg:col-span-7 lg:row-span-2">
            <Link
              to={`/products/${heroPiece.id}`}
              className="group relative block h-full min-h-[420px] w-full overflow-hidden rounded-lg text-left"
            >
              <ChocolateArt
                seed={heroPiece.id}
                variant={variantFor(heroPiece.name, heroPiece.id)}
                className="absolute inset-0 h-full w-full transition-transform duration-1000 ease-luxe group-hover:scale-105"
              />
              <div className="absolute inset-0 bg-gradient-to-t from-espresso/85 via-transparent to-transparent" />
              <div className="absolute inset-x-0 bottom-0 p-8 lg:p-10">
                <p className="text-xs uppercase tracking-[0.25em] text-gold">Seasonal Flagship</p>
                <h3 className="mt-2 font-display text-[22px] text-ivory lg:text-[28px]">{heroPiece.name}</h3>
                <p className="mt-2 max-w-md text-sm text-ivory/70">{heroPiece.description}</p>
                <span className="mt-4 inline-block border-b border-gold pb-0.5 text-sm text-gold">
                  {formatPrice(heroPiece.price)} — View the Piece
                </span>
              </div>
            </Link>
          </Reveal>

          {[second, third].map((product, i) => (
            <Reveal key={product.id} delay={(i + 1) * 150} className="lg:col-span-5">
              <Link
                to={`/products/${product.id}`}
                className="group relative block h-full min-h-[220px] w-full overflow-hidden rounded-lg text-left"
              >
                <ChocolateArt
                  seed={product.id}
                  variant={variantFor(product.name, product.id)}
                  className="absolute inset-0 h-full w-full transition-transform duration-1000 ease-luxe group-hover:scale-105"
                />
                <div className="absolute inset-0 bg-gradient-to-t from-espresso/85 via-transparent to-transparent" />
                <div className="absolute inset-x-0 bottom-0 p-7">
                  <h3 className="font-display text-xl text-ivory">{product.name}</h3>
                  <span className="mt-1 inline-block text-sm text-gold">{formatPrice(product.price)}</span>
                </div>
              </Link>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  );
}
