import { useEffect, useState } from 'react';
import { StarIcon } from './icons';
import { Reveal } from './Reveal';

const TESTIMONIALS = [
  {
    name: 'Elena Marinescu',
    location: 'Bucharest, Romania',
    rating: 5,
    text: 'The Madagascar bar tastes like nothing I have bought in a store — bright, almost like raspberries, then this long dark finish. I ordered three more before finishing the first.',
    initials: 'EM',
    hue: '#8E5F6B',
  },
  {
    name: 'James Whitfield',
    location: 'London, UK',
    rating: 5,
    text: 'Sent the Signature Gift Box to a client and they called me the same evening about it. The packaging alone feels like an event; the truffles are extraordinary.',
    initials: 'JW',
    hue: '#8C7F8A',
  },
  {
    name: 'Sofia Almeida',
    location: 'Lisbon, Portugal',
    rating: 5,
    text: 'You can genuinely taste the difference small batches make. The sea salt caramels have ruined every supermarket chocolate for me, permanently and happily.',
    initials: 'SA',
    hue: '#BF9A5F',
  },
];

const ROTATE_MS = 6000;

export function Testimonials() {
  const [index, setIndex] = useState(0);
  const [paused, setPaused] = useState(false);

  useEffect(() => {
    if (paused) return;
    const id = setInterval(() => setIndex((i) => (i + 1) % TESTIMONIALS.length), ROTATE_MS);
    return () => clearInterval(id);
  }, [paused]);

  return (
    <section
      className="bg-cocoa text-ivory"
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
    >
      <div className="mx-auto max-w-4xl px-5 py-24 text-center lg:px-8 lg:py-32">
        <Reveal>
          <p className="text-xs uppercase tracking-[0.3em] text-gold">Word of Mouth</p>
          <h2 className="mt-4 font-display text-[32px] leading-tight lg:text-[48px]">Savored Worldwide</h2>
        </Reveal>

        <div className="relative mt-14 min-h-[280px]" aria-live="polite">
          {TESTIMONIALS.map((t, i) => (
            <figure
              key={t.name}
              className={`absolute inset-0 flex flex-col items-center transition-opacity duration-1000 ease-luxe ${
                i === index ? 'opacity-100' : 'pointer-events-none opacity-0'
              }`}
              aria-hidden={i !== index}
            >
              <div
                className="flex h-16 w-16 items-center justify-center rounded-full font-display text-xl text-espresso"
                style={{ backgroundColor: t.hue }}
                aria-hidden="true"
              >
                {t.initials}
              </div>
              <div className="mt-4 flex gap-1 text-gold">
                {[...Array(t.rating)].map((_, s) => (
                  <StarIcon key={s} />
                ))}
              </div>
              <blockquote className="mt-6 max-w-2xl font-display text-xl italic leading-relaxed lg:text-2xl">
                “{t.text}”
              </blockquote>
              <figcaption className="mt-6 text-sm">
                <span className="text-ivory">{t.name}</span>
                <span className="text-ivory/50"> — {t.location}</span>
              </figcaption>
            </figure>
          ))}
        </div>

        <div className="mt-10 flex justify-center gap-2" role="tablist" aria-label="Testimonials">
          {TESTIMONIALS.map((t, i) => (
            <button
              key={t.name}
              role="tab"
              aria-selected={i === index}
              aria-label={`Testimonial from ${t.name}`}
              onClick={() => setIndex(i)}
              className={`h-1.5 rounded-full transition-all duration-500 ease-luxe ${
                i === index ? 'w-10 bg-gold' : 'w-5 bg-ivory/25'
              }`}
            />
          ))}
        </div>
      </div>
    </section>
  );
}
