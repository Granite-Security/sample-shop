import { useEffect, useState } from 'react';
import { initialsOf, monogramColor } from '../utils/avatar';

/**
 * An avatar image that degrades to an initials monogram.
 *
 * The fallback is not decoration: a Google picture URL rotates when the user
 * changes their photo, so a cached one can 404 at any time and must never leave
 * a broken-image icon behind (docs/users/user-pic.md §1).
 */
export function Avatar({ src, name, size = 40, ring = false }: {
  src?: string | null;
  name: string;
  size?: number;
  /** Thin gold ring — used where the avatar is the subject, not an accessory. */
  ring?: boolean;
}) {
  const [failed, setFailed] = useState(false);

  // Without this, swapping to a new picture keeps showing the monogram because
  // `failed` is still true from the previous URL.
  useEffect(() => { setFailed(false); }, [src]);

  const ringClass = ring ? 'ring-1 ring-gold ring-offset-2 ring-offset-ivory' : '';

  if (!src || failed) {
    return (
      <span
        title={name}
        aria-hidden="true"
        className={`inline-flex shrink-0 select-none items-center justify-center rounded-full font-display text-ivory ${ringClass}`}
        style={{
          width: size,
          height: size,
          background: monogramColor(name),
          fontSize: Math.max(11, Math.round(size * 0.38)),
        }}
      >
        {initialsOf(name)}
      </span>
    );
  }

  return (
    <img
      src={src}
      alt=""
      title={name}
      width={size}
      height={size}
      // Google serves the picture from lh3.googleusercontent.com; sending our
      // referrer there on every render leaks the page view for no benefit.
      referrerPolicy="no-referrer"
      onError={() => setFailed(true)}
      className={`shrink-0 rounded-full bg-cocoa/10 object-cover ${ringClass}`}
      style={{ width: size, height: size }}
    />
  );
}
