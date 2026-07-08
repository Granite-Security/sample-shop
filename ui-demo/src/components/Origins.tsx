import { useState } from 'react';
import { Reveal } from './Reveal';

interface Region {
  name: string;
  country: string;
  /** position on the stylized map, in viewBox units */
  x: number;
  y: number;
  notes: string;
  sustainability: string;
}

const REGIONS: Region[] = [
  {
    name: 'Los Ríos',
    country: 'Ecuador',
    x: 235,
    y: 310,
    notes: 'Dried fig, jasmine, toasted hazelnut',
    sustainability: 'Agroforestry plots shaded by banana and mahogany; direct trade since 2019.',
  },
  {
    name: 'Ashanti',
    country: 'Ghana',
    x: 465,
    y: 285,
    notes: 'Brown butter, honey, warm cocoa',
    sustainability: 'Farmer-owned fermentary; premiums fund village solar dryers.',
  },
  {
    name: 'Sambirano Valley',
    country: 'Madagascar',
    x: 590,
    y: 340,
    notes: 'Red berry, citrus zest, deep cacao',
    sustainability: 'Heirloom Criollo preservation and reforested river buffers.',
  },
];

/** Simplified world landmasses — enough silhouette to read as a map without external assets. */
const LANDMASSES = [
  // americas
  'M120 90 C 160 70, 210 80, 230 110 C 250 140, 230 170, 245 200 C 260 230, 250 270, 235 300 C 222 330, 205 360, 195 340 C 185 315, 200 285, 190 260 C 175 230, 150 215, 140 185 C 128 150, 95 115, 120 90 Z',
  // europe + africa
  'M420 100 C 450 85, 490 90, 505 110 C 520 128, 505 145, 520 160 C 545 180, 540 220, 525 255 C 512 290, 495 330, 475 345 C 458 355, 445 330, 440 300 C 432 265, 415 240, 420 205 C 424 175, 405 140, 420 100 Z',
  // asia + oceania
  'M560 95 C 610 75, 680 85, 720 110 C 755 132, 745 165, 720 185 C 695 205, 665 195, 645 215 C 628 235, 605 230, 590 210 C 572 188, 545 170, 550 140 C 553 120, 545 102, 560 95 Z M 690 300 C 715 290, 740 300, 745 320 C 748 338, 725 350, 705 345 C 688 340, 672 310, 690 300 Z',
  // madagascar
  'M585 320 C 595 312, 602 322, 600 340 C 598 355, 588 362, 582 350 C 577 340, 578 327, 585 320 Z',
];

export function Origins() {
  const [active, setActive] = useState(0);
  const region = REGIONS[active];

  return (
    <section className="bg-ivory">
      <div className="mx-auto max-w-7xl px-5 py-24 lg:px-8 lg:py-32">
        <Reveal>
          <p className="text-center text-xs uppercase tracking-[0.3em] text-terracotta">Cacao Origins</p>
          <h2 className="mt-4 text-center font-display text-[32px] leading-tight text-cocoa lg:text-[48px]">
            Where Our Chocolate Begins
          </h2>
        </Reveal>

        <div className="mt-16 grid items-center gap-12 lg:grid-cols-[3fr_2fr]">
          <Reveal>
            <svg viewBox="0 0 800 450" className="w-full" role="img" aria-label="Map of cacao sourcing regions">
              <rect width="800" height="450" rx="12" fill="#1E1612" />
              {LANDMASSES.map((d, i) => (
                <path key={i} d={d} fill="#3A2418" stroke="#5A3A26" strokeWidth="1" />
              ))}
              {/* equator hint */}
              <line x1="0" y1="290" x2="800" y2="290" stroke="#C7A56B" strokeWidth="0.6" strokeDasharray="4 8" opacity="0.35" />
              {REGIONS.map((r, i) => (
                <g
                  key={r.name}
                  onClick={() => setActive(i)}
                  onMouseEnter={() => setActive(i)}
                  className="cursor-pointer"
                >
                  <circle cx={r.x} cy={r.y} r="16" fill="#C7A56B" opacity={active === i ? 0.25 : 0.1}>
                    <animate attributeName="r" values="12;20;12" dur="3s" repeatCount="indefinite" />
                  </circle>
                  <circle
                    cx={r.x}
                    cy={r.y}
                    r="5"
                    fill={active === i ? '#C7A56B' : '#A56A52'}
                    stroke="#F7F3EE"
                    strokeWidth="1.5"
                  />
                  <text
                    x={r.x}
                    y={r.y - 16}
                    textAnchor="middle"
                    fontFamily="Inter, sans-serif"
                    fontSize="13"
                    fill={active === i ? '#F7F3EE' : '#F7F3EE99'}
                  >
                    {r.country}
                  </text>
                </g>
              ))}
            </svg>
          </Reveal>

          <Reveal delay={200}>
            <div className="rounded-lg border border-cocoa/10 bg-white/50 p-8 lg:p-10" aria-live="polite">
              <p className="text-xs uppercase tracking-[0.25em] text-terracotta">{region.country}</p>
              <h3 className="mt-2 font-display text-[22px] text-cocoa lg:text-[28px]">{region.name}</h3>
              <dl className="mt-6 space-y-5 text-sm">
                <div>
                  <dt className="text-xs uppercase tracking-[0.2em] text-cocoa/50">Flavor Notes</dt>
                  <dd className="mt-1 font-display text-lg italic text-cocoa">{region.notes}</dd>
                </div>
                <div>
                  <dt className="text-xs uppercase tracking-[0.2em] text-cocoa/50">Sustainability</dt>
                  <dd className="mt-1 text-cocoa/70">{region.sustainability}</dd>
                </div>
              </dl>
              <div className="mt-8 flex gap-2" role="tablist" aria-label="Sourcing regions">
                {REGIONS.map((r, i) => (
                  <button
                    key={r.name}
                    role="tab"
                    aria-selected={active === i}
                    aria-label={r.name}
                    onClick={() => setActive(i)}
                    className={`h-1.5 rounded-full transition-all duration-500 ease-luxe ${
                      active === i ? 'w-10 bg-gold' : 'w-5 bg-cocoa/20'
                    }`}
                  />
                ))}
              </div>
            </div>
          </Reveal>
        </div>
      </div>
    </section>
  );
}
