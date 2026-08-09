import type { MediaItem } from '../types';

// Product media may now include video (video/mp4, video/webm — the two formats
// StorageService accepts for the products scope). Only the gallery knows how to
// play one; everything else renders an <img>, which would show a broken tile.
export function isVideoMedia(item: MediaItem): boolean {
  return item.contentType?.startsWith('video/') ?? false;
}

// Treats a missing contentType as an image: rows predate the video work and
// were all images.
export function isImageMedia(item: MediaItem): boolean {
  return !isVideoMedia(item);
}

// Mirrors ui-shop/src/utils/media.ts.
// Explicit isDefault wins; a single uploaded image is treated as the default
// even without the flag (auto-select), but that stops once a second image
// exists — the admin must then pick one explicitly.
//
// Images only, deliberately. This is the product's thumbnail — used by cards,
// admin lists and order rows, all of which render it as an <img>. Keeping video
// out here is what lets those stay unchanged and safe; a piece whose only media
// is a video falls back to its illustration rather than a broken image.
export function getDefaultMedia(media: MediaItem[] | undefined | null): MediaItem | null {
  if (!media || media.length === 0) return null;
  const images = media.filter(isImageMedia);
  if (images.length === 0) return null;
  const explicit = images.find((m) => m.isDefault);
  if (explicit) return explicit;
  if (images.length === 1) return images[0];
  return null;
}
