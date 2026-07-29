import { Link } from 'react-router';
import { ChocolateArt } from './ChocolateArt';
import { ArrowIcon } from './icons';
import { Reveal } from './Reveal';

/**
 * A short invitation into /our-story — the long-form craft and origins
 * content lives there now, not on the home page.
 */
export function StoryTeaser() {
  return (
    <section className="bg-espresso text-ivory">
      <div className="mx-auto grid max-w-7xl items-center gap-12 px-5 py-24 lg:grid-cols-2 lg:gap-20 lg:px-8 lg:py-32">
        <Reveal>
          <div className="aspect-[4/3] overflow-hidden rounded-lg">
            <ChocolateArt seed={5} variant="bar" className="h-full w-full" />
          </div>
        </Reveal>

        <Reveal delay={200}>
          <p className="text-xs uppercase tracking-[0.3em] text-gold">Our Story</p>
          <h2 className="mt-4 font-display text-[32px] leading-tight lg:text-[48px]">
            A Kitchen in Cahul, a Following in Iași
          </h2>
          <p className="mt-6 max-w-lg text-ivory/70">
            SI Chocolate was created by Inga Miron in Cahul, Moldova, and found its audience in Iași,
            Romania — where the chocolate was appreciated enough to turn a small kitchen into an
            atelier. Everything is still made in small batches, still finished by hand.
          </p>
          <Link
            to="/our-story"
            className="mt-10 inline-flex items-center gap-3 border border-ivory/40 px-9 py-4 text-xs uppercase tracking-[0.2em] text-ivory transition-all duration-500 ease-luxe hover:border-gold hover:text-gold"
          >
            Read our story <ArrowIcon />
          </Link>
        </Reveal>
      </div>
    </section>
  );
}
