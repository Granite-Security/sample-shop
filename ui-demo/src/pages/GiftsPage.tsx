import { Link } from 'react-router';
import { ChocolateArt } from '../components/ChocolateArt';
import { Reveal } from '../components/Reveal';
import { ArrowIcon } from '../components/icons';

const GIFT_TIERS = [
  {
    title: 'Corporate Gifts',
    body: 'Branded keepsake boxes for clients and teams, from ten to ten thousand.',
    seed: 3,
    variant: 'gift' as const,
  },
  {
    title: 'Holiday Collections',
    body: 'Limited seasonal assortments released four times a year — and never repeated.',
    seed: 9,
    variant: 'truffle' as const,
  },
  {
    title: 'Personalized Notes',
    body: 'Every box can carry a hand-written card, sealed in gold wax.',
    seed: 6,
    variant: 'bar' as const,
  },
];

const STEPS = [
  { step: '01', title: 'Choose the pieces', body: 'Pick a ready assortment, or tell us the palate and we will build one.' },
  { step: '02', title: 'Add the note', body: 'A hand-written card, sealed in gold wax, in your words.' },
  { step: '03', title: 'We send it', body: 'Packed cold in season, tracked to the door, timed to the date you name.' },
];

export function GiftsPage() {
  return (
    <>
      <section className="relative overflow-hidden bg-espresso pt-32 pb-20 lg:pt-40 lg:pb-28">
        <div className="absolute inset-0 animate-drift">
          <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_35%_25%,#3A2130_0%,#150E13_62%,#120D0A_100%)]" />
          <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_70%_85%,rgba(199,165,107,0.14)_0%,transparent_52%)]" />
        </div>
        <div className="relative mx-auto max-w-3xl px-5 text-center lg:px-8">
          <Reveal>
            <p className="text-xs uppercase tracking-[0.3em] text-gold">Gifting</p>
            <h1 className="mt-4 font-display text-[36px] leading-tight text-ivory lg:text-[56px]">
              Give Something They'll Remember Tasting
            </h1>
            <p className="mx-auto mt-6 max-w-xl text-lg text-ivory/75">
              Boxes tied by hand, for the occasions that deserve more than a card.
            </p>
          </Reveal>
        </div>
      </section>

      <section className="bg-ivory">
        <div className="mx-auto max-w-7xl px-5 py-24 lg:px-8 lg:py-32">
          <div className="grid gap-8 md:grid-cols-3">
            {GIFT_TIERS.map((tier, i) => (
              <Reveal key={tier.title} delay={i * 150}>
                <article className="group h-full overflow-hidden rounded-lg bg-white/50">
                  <div className="aspect-[4/3] overflow-hidden">
                    <ChocolateArt
                      seed={tier.seed}
                      variant={tier.variant}
                      className="h-full w-full transition-transform duration-1000 ease-luxe group-hover:scale-108"
                    />
                  </div>
                  <div className="p-8">
                    <h2 className="font-display text-[22px] text-cocoa lg:text-[26px]">{tier.title}</h2>
                    <p className="mt-3 text-sm text-cocoa/65">{tier.body}</p>
                  </div>
                </article>
              </Reveal>
            ))}
          </div>
        </div>
      </section>

      <section className="bg-espresso text-ivory">
        <div className="mx-auto max-w-7xl px-5 py-24 lg:px-8 lg:py-28">
          <Reveal>
            <p className="text-center text-xs uppercase tracking-[0.3em] text-gold">How Gifting Works</p>
            <h2 className="mt-4 text-center font-display text-[32px] leading-tight lg:text-[44px]">
              Three Steps, No Guesswork
            </h2>
          </Reveal>

          <div className="mt-16 grid gap-12 md:grid-cols-3">
            {STEPS.map((item, i) => (
              <Reveal key={item.step} delay={i * 150}>
                <p className="font-display text-[40px] text-gold/40">{item.step}</p>
                <h3 className="mt-3 font-display text-[22px]">{item.title}</h3>
                <p className="mt-2 text-ivory/65">{item.body}</p>
              </Reveal>
            ))}
          </div>

          <Reveal delay={200}>
            <div className="mt-16 flex flex-wrap justify-center gap-4">
              <Link
                to="/shop"
                className="inline-flex items-center gap-3 bg-gold px-9 py-4 text-xs uppercase tracking-[0.2em] text-espresso transition-all duration-500 ease-luxe hover:bg-ivory"
              >
                Shop Gifts <ArrowIcon />
              </Link>
              <Link
                to="/contact"
                className="inline-flex items-center gap-3 border border-ivory/40 px-9 py-4 text-xs uppercase tracking-[0.2em] text-ivory transition-all duration-500 ease-luxe hover:border-gold hover:text-gold"
              >
                Talk to us about a large order
              </Link>
            </div>
          </Reveal>
        </div>
      </section>
    </>
  );
}
