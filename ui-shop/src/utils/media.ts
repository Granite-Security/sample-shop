import type { MediaItem } from '../types';

// Explicit isDefault wins; a single uploaded image is treated as the default
// even without the flag (auto-select), but that stops once a second image
// exists — the admin must then pick one explicitly.
export function getDefaultMedia(media: MediaItem[] | undefined | null): MediaItem | null {
  if (!media || media.length === 0) return null;
  const explicit = media.find(m => m.isDefault);
  if (explicit) return explicit;
  if (media.length === 1) return media[0];
  return null;
}
