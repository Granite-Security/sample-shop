import { ChocolateArt } from './ChocolateArt';
import { HandIcon, LeafIcon, TruckIcon } from './icons';
import { Reveal } from './Reveal';

const CHAPTERS = [
  {
    icon: LeafIcon,
    kicker: 'Chapter One',
    title: 'Ethical Sourcing',
    body: 'We buy cacao directly from twelve family farms across Ecuador, Ghana and Madagascar, paying two to three times the commodity rate. Every harvest is traceable to the grower who fermented it.',
    quote: '“Great chocolate begins years before the first bean is roasted.”',
    seed: 5,
    variant: 'truffle' as const,
  },
  {
    icon: HandIcon,
    kicker: 'Chapter Two',
    title: 'Small-Batch Production',
    body: 'Our batches never exceed forty kilograms. Stone grinders run for three days, coaxing out flavor that industrial lines conche away in hours. Slow is not a limitation — it is the recipe.',
    quote: '“Forty kilograms at a time. Never more.”',
    seed: 6,
    variant: 'bar' as const,
  },
  {
    icon: TruckIcon,
    kicker: 'Chapter Three',
    title: 'Artisan Craftsmanship',
    body: 'Each truffle is rolled, dipped and finished by one of our six chocolatiers. Tempering is done by feel on marble — the snap of a finished bar is our signature, not a machine setting.',
    quote: '“The snap of the bar is our signature.”',
    seed: 7,
    variant: 'drink' as const,
  },
];

export function Craft() {
  return (
    <section id="craft" className="bg-espresso text-ivory">
      <div className="mx-auto max-w-7xl px-5 py-24 lg:px-8 lg:py-32">
        <Reveal>
          <p className="text-center text-xs uppercase tracking-[0.3em] text-gold">Our Craft</p>
          <h2 className="mt-4 text-center font-display text-[32px] leading-tight lg:text-[48px]">
            From Grove to Gold Foil
          </h2>
        </Reveal>

        <div className="mt-20 space-y-24 lg:space-y-32">
          {CHAPTERS.map((chapter, i) => {
            const Icon = chapter.icon;
            const reversed = i % 2 === 1;
            return (
              <div
                key={chapter.title}
                className={`grid items-center gap-10 lg:grid-cols-2 lg:gap-20 ${
                  reversed ? 'lg:[&>*:first-child]:order-2' : ''
                }`}
              >
                <Reveal className="relative">
                  <div className="aspect-[4/3] overflow-hidden rounded-lg">
                    <ChocolateArt seed={chapter.seed} variant={chapter.variant} className="h-full w-full" />
                  </div>
                  <blockquote
                    className={`absolute -bottom-6 max-w-[75%] bg-gold px-6 py-4 font-display text-lg italic text-espresso shadow-xl ${
                      reversed ? 'right-4 lg:-right-8' : 'left-4 lg:-left-8'
                    }`}
                  >
                    {chapter.quote}
                  </blockquote>
                </Reveal>
                <Reveal delay={200}>
                  <div className="flex items-center gap-3 text-gold">
                    <Icon />
                    <span className="text-xs uppercase tracking-[0.3em]">{chapter.kicker}</span>
                  </div>
                  <h3 className="mt-4 font-display text-[22px] lg:text-[28px]">{chapter.title}</h3>
                  <p className="mt-4 max-w-lg text-ivory/70">{chapter.body}</p>
                </Reveal>
              </div>
            );
          })}
        </div>
      </div>
    </section>
  );
}
