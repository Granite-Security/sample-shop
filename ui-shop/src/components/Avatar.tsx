import { useEffect, useState } from 'react';
import { initialsOf, monogramColor } from '../utils/avatar';

/**
 * An avatar image that degrades to an initials monogram.
 *
 * <p>The fallback is not decoration: a Google picture URL rotates when the user
 * changes their photo, so a cached one can 404 at any time and must never leave
 * a broken-image icon behind (docs/users/user-pic.md §1).
 */
export default function Avatar({ src, name, size = 40, title }: {
  src?: string | null;
  name: string;
  size?: number;
  title?: string;
}) {
  const [failed, setFailed] = useState(false);

  // Without this, swapping to a new picture keeps showing the monogram because
  // `failed` is still true from the previous URL.
  useEffect(() => { setFailed(false); }, [src]);

  const shared = {
    width: size,
    height: size,
    borderRadius: '50%',
    flexShrink: 0,
    objectFit: 'cover' as const,
  };

  if (!src || failed) {
    return (
      <span
        title={title ?? name}
        aria-hidden="true"
        style={{
          ...shared,
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          background: monogramColor(name),
          color: '#fff',
          fontSize: Math.max(11, Math.round(size * 0.38)),
          fontWeight: 600,
          lineHeight: 1,
          userSelect: 'none',
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
      title={title ?? name}
      width={size}
      height={size}
      // Google serves the picture from lh3.googleusercontent.com; sending our
      // referrer there on every render leaks the page view for no benefit.
      referrerPolicy="no-referrer"
      onError={() => setFailed(true)}
      style={{ ...shared, background: 'var(--bg-secondary)' }}
    />
  );
}
