import { Link } from 'react-router';
import { ChocolateArt } from '../components/ChocolateArt';
import { Craft } from '../components/Craft';
import { Origins } from '../components/Origins';
import { Reveal } from '../components/Reveal';
import { ArrowIcon } from '../components/icons';

const MILESTONES = [
  {
    place: 'Cahul, Moldova',
    label: 'Where it started',
    body: 'Inga Miron began tempering chocolate in a small kitchen in Cahul, working in batches of a few kilograms at a time. The first boxes went to neighbours, then to a handful of local shops that kept asking for more.',
  },
  {
    place: 'Iași, Romania',
    label: 'Where it was understood',
    body: 'It was in Iași that the work found its audience. Romanian pastry shops, tasting rooms and gift buyers took to it immediately, and the reception there turned a Cahul kitchen into an atelier with a following across the border.',
  },
  {
    place: 'Today',
    label: 'Where it is going',
    body: 'Everything is still made in small batches, still finished by hand, and still recognisably the chocolate Inga started with — only now there is a great deal more of it, and a great many more people waiting for each release.',
  },
];

export function OurStoryPage() {
  return (
    <>
      <section className="relative overflow-hidden bg-espresso pt-32 pb-20 lg:pt-40 lg:pb-28">
        <div className="absolute inset-0 animate-drift">
          <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_30%_25%,#3A2130_0%,#150E13_62%,#120D0A_100%)]" />
          <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_75%_85%,rgba(199,165,107,0.13)_0%,transparent_52%)]" />
        </div>
        <div className="relative mx-auto max-w-3xl px-5 text-center lg:px-8">
          <Reveal>
            <p className="text-xs uppercase tracking-[0.3em] text-gold">Our Story</p>
            <h1 className="mt-4 font-display text-[36px] leading-tight text-ivory lg:text-[56px]">
              Begun in a Kitchen in Cahul
            </h1>
            <p className="mx-auto mt-6 max-w-xl text-lg text-ivory/75">
              An artisan chocolatier founded by Inga Miron — born in Moldova, embraced in Romania.
            </p>
          </Reveal>
        </div>
      </section>

      {/* The founding narrative, told as three places rather than three dates. */}
      <section className="bg-ivory">
        <div className="mx-auto max-w-7xl px-5 py-24 lg:px-8 lg:py-32">
          <div className="grid items-start gap-12 lg:grid-cols-[2fr_3fr] lg:gap-20">
            <Reveal>
              <div className="relative">
                <div className="aspect-[4/5] overflow-hidden rounded-lg">
                  <ChocolateArt seed={4} variant="truffle" className="h-full w-full" />
                </div>
                {/* Stays inside the image column — a negative offset here runs
                    off the viewport, since this column starts at page padding. */}
                <blockquote className="absolute -bottom-6 left-4 max-w-[80%] bg-gold px-6 py-4 font-display text-lg italic text-espresso shadow-xl">
                  “Small batches, made properly, travel further than big ones.”
                </blockquote>
              </div>
            </Reveal>

            <Reveal delay={150}>
              <p className="text-xs uppercase tracking-[0.3em] text-terracotta">The Founder</p>
              <h2 className="mt-4 font-display text-[32px] leading-tight text-cocoa lg:text-[44px]">
                Inga Miron
              </h2>
              <p className="mt-6 text-cocoa/70">
                SI Chocolate was created by Inga Miron in Cahul, in the south of Moldova. What began
                as a handful of hand-tempered bars made for family and neighbours grew, batch by
                batch, into a full atelier — without ever changing the method it started with.
              </p>
              <p className="mt-4 text-cocoa/70">
                Its major success came in Iași, Romania, where the chocolate was highly appreciated
                and quickly found a devoted audience. That reception is what turned a small Moldovan
                kitchen into the chocolatier it is today, and Iași remains the city where SI
                Chocolate is best known and most loved.
              </p>

              <ul className="mt-12 space-y-8">
                {MILESTONES.map((milestone) => (
                  <li key={milestone.place} className="border-l-2 border-gold/40 pl-6">
                    <p className="text-[11px] uppercase tracking-[0.22em] text-terracotta">
                      {milestone.label}
                    </p>
                    <h3 className="mt-1 font-display text-[22px] text-cocoa">{milestone.place}</h3>
                    <p className="mt-2 text-cocoa/65">{milestone.body}</p>
                  </li>
                ))}
              </ul>
            </Reveal>
          </div>
        </div>
      </section>

      <Craft />
      <Origins />

      <section className="border-t border-cocoa/10 bg-ivory">
        <div className="mx-auto max-w-2xl px-5 py-20 text-center lg:py-24">
          <Reveal>
            <h2 className="font-display text-[28px] leading-tight text-cocoa lg:text-[40px]">
              Taste What Iași Fell For
            </h2>
            <Link
              to="/shop"
              className="mt-8 inline-flex items-center gap-3 bg-cocoa px-9 py-4 text-xs uppercase tracking-[0.2em] text-ivory transition-colors duration-500 ease-luxe hover:bg-espresso"
            >
              Visit the Shop <ArrowIcon />
            </Link>
          </Reveal>
        </div>
      </section>
    </>
  );
}
