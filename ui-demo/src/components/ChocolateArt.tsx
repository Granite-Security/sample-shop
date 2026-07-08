// Generated product artwork — the brand has no photography yet, so every
// visual is drawn from layered gradients and SVG. Deterministic per seed so
// the same product always gets the same composition.

type Variant = 'bar' | 'truffle' | 'gift' | 'drink';

const PALETTES = [
  { deep: '#1E1612', mid: '#3A2418', high: '#5A3A26', glow: '#C7A56B' },
  { deep: '#241812', mid: '#46281A', high: '#6B4530', glow: '#D8B98A' },
  { deep: '#1B1410', mid: '#33231C', high: '#A56A52', glow: '#C7A56B' },
  { deep: '#201711', mid: '#3E2A1C', high: '#8B9172', glow: '#D8C39A' },
];

export function variantFor(name: string, id: number): Variant {
  const n = name.toLowerCase();
  if (/(truffle|praline|ganache|bonbon|collection)/.test(n)) return 'truffle';
  if (/(gift|box|set|hamper)/.test(n)) return 'gift';
  if (/(drink|hot|flake|cocoa powder|powder)/.test(n)) return 'drink';
  if (/(bar|origin|%)/.test(n)) return 'bar';
  const variants: Variant[] = ['bar', 'truffle', 'gift', 'drink'];
  return variants[Math.abs(id) % variants.length];
}

interface ArtProps {
  seed: number;
  variant?: Variant;
  className?: string;
}

/** A soft, editorial illustration of chocolate — bar, truffles, gift box or drink. */
export function ChocolateArt({ seed, variant = 'bar', className = '' }: ArtProps) {
  const p = PALETTES[Math.abs(seed) % PALETTES.length];
  const uid = `art-${variant}-${Math.abs(seed)}`;

  return (
    <svg
      viewBox="0 0 400 400"
      className={className}
      role="img"
      aria-label="Illustration of artisan chocolate"
      preserveAspectRatio="xMidYMid slice"
    >
      <defs>
        <radialGradient id={`${uid}-bg`} cx="35%" cy="25%" r="90%">
          <stop offset="0%" stopColor={p.mid} />
          <stop offset="100%" stopColor={p.deep} />
        </radialGradient>
        <linearGradient id={`${uid}-face`} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stopColor={p.high} />
          <stop offset="55%" stopColor={p.mid} />
          <stop offset="100%" stopColor={p.deep} />
        </linearGradient>
        <linearGradient id={`${uid}-sheen`} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stopColor="#FFFFFF" stopOpacity="0.35" />
          <stop offset="45%" stopColor="#FFFFFF" stopOpacity="0.05" />
          <stop offset="100%" stopColor="#FFFFFF" stopOpacity="0" />
        </linearGradient>
        <radialGradient id={`${uid}-halo`} cx="50%" cy="42%" r="55%">
          <stop offset="0%" stopColor={p.glow} stopOpacity="0.28" />
          <stop offset="100%" stopColor={p.glow} stopOpacity="0" />
        </radialGradient>
      </defs>

      <rect width="400" height="400" fill={`url(#${uid}-bg)`} />
      <circle cx="200" cy="185" r="190" fill={`url(#${uid}-halo)`} />

      {variant === 'bar' && (
        <g transform="rotate(-8 200 200)">
          <rect x="95" y="105" width="210" height="200" rx="14" fill={p.deep} opacity="0.55" transform="translate(10 12)" />
          <rect x="95" y="105" width="210" height="200" rx="12" fill={`url(#${uid}-face)`} />
          {[0, 1, 2].map((r) =>
            [0, 1].map((c) => (
              <rect
                key={`${r}-${c}`}
                x={110 + c * 95}
                y={120 + r * 62}
                width="80"
                height="50"
                rx="8"
                fill={p.mid}
                stroke={p.high}
                strokeOpacity="0.5"
                strokeWidth="1.5"
              />
            )),
          )}
          <rect x="95" y="105" width="210" height="200" rx="12" fill={`url(#${uid}-sheen)`} />
          <text
            x="200"
            y="218"
            textAnchor="middle"
            fontFamily="Playfair Display, Georgia, serif"
            fontSize="26"
            fill={p.glow}
            opacity="0.9"
          >
            SI
          </text>
        </g>
      )}

      {variant === 'truffle' && (
        <g>
          <ellipse cx="200" cy="318" rx="130" ry="18" fill={p.deep} opacity="0.6" />
          <circle cx="145" cy="240" r="58" fill={`url(#${uid}-face)`} />
          <circle cx="256" cy="252" r="46" fill={p.mid} />
          <circle cx="212" cy="168" r="64" fill={`url(#${uid}-face)`} />
          <circle cx="188" cy="146" r="20" fill="#FFFFFF" opacity="0.18" />
          <circle cx="128" cy="222" r="14" fill="#FFFFFF" opacity="0.14" />
          <circle cx="244" cy="238" r="10" fill="#FFFFFF" opacity="0.12" />
          {[0, 1, 2, 3, 4].map((i) => (
            <circle
              key={i}
              cx={150 + ((seed * 37 + i * 53) % 130)}
              cy={120 + ((seed * 61 + i * 41) % 40)}
              r="2.2"
              fill={p.glow}
              opacity="0.85"
            />
          ))}
        </g>
      )}

      {variant === 'gift' && (
        <g transform="rotate(-4 200 210)">
          <rect x="105" y="170" width="190" height="130" rx="8" fill={`url(#${uid}-face)`} />
          <rect x="95" y="140" width="210" height="44" rx="8" fill={p.mid} />
          <rect x="188" y="140" width="24" height="160" fill={p.glow} opacity="0.9" />
          <path
            d="M200 138 C 168 92, 118 108, 146 138 Z M200 138 C 232 92, 282 108, 254 138 Z"
            fill={p.glow}
            opacity="0.95"
          />
          <rect x="105" y="170" width="190" height="130" rx="8" fill={`url(#${uid}-sheen)`} />
        </g>
      )}

      {variant === 'drink' && (
        <g>
          <ellipse cx="200" cy="322" rx="120" ry="16" fill={p.deep} opacity="0.6" />
          <path d="M128 160 L146 310 A14 14 0 0 0 160 322 L240 322 A14 14 0 0 0 254 310 L272 160 Z" fill={`url(#${uid}-face)`} />
          <ellipse cx="200" cy="160" rx="72" ry="20" fill={p.mid} />
          <ellipse cx="200" cy="160" rx="58" ry="14" fill={p.high} />
          <ellipse cx="200" cy="160" rx="58" ry="14" fill={`url(#${uid}-sheen)`} />
          {[0, 1, 2].map((i) => (
            <path
              key={i}
              d={`M${172 + i * 26} 128 q 8 -18 0 -34 q -8 -14 0 -28`}
              stroke={p.glow}
              strokeWidth="3"
              strokeLinecap="round"
              fill="none"
              opacity={0.5 - i * 0.1}
            />
          ))}
        </g>
      )}

      {/* film grain */}
      <rect width="400" height="400" fill={p.glow} opacity="0.03" />
    </svg>
  );
}
