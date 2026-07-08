import { ChocolateArt } from './ChocolateArt';
import { ArrowIcon } from './icons';
import { Reveal } from './Reveal';

const GIFT_TIERS = [
  {
    title: 'Corporate Gifts',
    body: 'Branded keepsake boxes for clients and teams, from ten to ten thousand.',
  },
  {
    title: 'Holiday Collections',
    body: 'Limited seasonal assortments released four times a year — and never repeated.',
  },
  {
    title: 'Personalized Notes',
    body: 'Every box can carry a hand-written card, sealed in gold wax.',
  },
];

export function Gifting() {
  return (
    <section id="gifting" className="bg-espresso text-ivory">
      <div className="mx-auto grid max-w-7xl items-center gap-12 px-5 py-24 lg:grid-cols-2 lg:gap-20 lg:px-8 lg:py-32">
        <Reveal>
          <div className="relative">
            <div className="aspect-[4/5] max-w-md overflow-hidden rounded-lg">
              <ChocolateArt seed={3} variant="gift" className="h-full w-full" />
            </div>
            <div className="absolute -bottom-8 -right-4 hidden aspect-square w-48 overflow-hidden rounded-lg border-4 border-espresso sm:block lg:-right-10">
              <ChocolateArt seed={9} variant="truffle" className="h-full w-full" />
            </div>
          </div>
        </Reveal>

        <Reveal delay={200}>
          <p className="text-xs uppercase tracking-[0.3em] text-gold">Gifting</p>
          <h2 className="mt-4 font-display text-[32px] leading-tight lg:text-[48px]">
            Give Something They'll Remember Tasting
          </h2>
          <ul className="mt-10 space-y-8">
            {GIFT_TIERS.map((tier) => (
              <li key={tier.title} className="border-l-2 border-gold/40 pl-6">
                <h3 className="font-display text-[22px]">{tier.title}</h3>
                <p className="mt-1 text-ivory/65">{tier.body}</p>
              </li>
            ))}
          </ul>
          <a
            href="#bestsellers"
            className="mt-10 inline-flex items-center gap-3 bg-gold px-9 py-4 text-xs uppercase tracking-[0.2em] text-espresso transition-all duration-500 ease-luxe hover:bg-ivory"
          >
            Shop Gifts <ArrowIcon />
          </a>
        </Reveal>
      </div>
    </section>
  );
}
