/**
 * Center-crops to a square and scales down before upload, so a 12 MP phone
 * photo becomes a ~50 KB avatar instead of a 6 MB one. This is the primary
 * size control: the upload goes straight from the browser to storage under a
 * presigned URL, so the server can only check the size a client *declares*
 * (docs/users/user-pic.md D2).
 */
export async function downscaleToSquare(file: File, size = 512): Promise<File> {
  const bitmap = await createImageBitmap(file);
  try {
    const side = Math.min(bitmap.width, bitmap.height);
    const canvas = document.createElement('canvas');
    canvas.width = size;
    canvas.height = size;
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('Could not process the image in this browser.');
    ctx.drawImage(
      bitmap,
      (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side,
      0, 0, size, size,
    );
    const blob = await new Promise<Blob | null>(resolve =>
      canvas.toBlob(resolve, 'image/jpeg', 0.85));
    if (!blob) throw new Error('Could not process the image.');
    return new File([blob], 'avatar.jpg', { type: 'image/jpeg' });
  } finally {
    bitmap.close();
  }
}

const MONOGRAM_COLORS = [
  '#6b4d8f', '#1f6f5c', '#8a5a3c', '#345b8f', '#7a3f56', '#4a6b2f', '#8f6b1f',
];

/** Stable per user, so the same person always gets the same monogram colour. */
export function monogramColor(seed: string): string {
  let hash = 0;
  for (let i = 0; i < seed.length; i++) {
    hash = (hash * 31 + seed.charCodeAt(i)) | 0;
  }
  return MONOGRAM_COLORS[Math.abs(hash) % MONOGRAM_COLORS.length];
}

export function initialsOf(name: string): string {
  const parts = name.trim().split(/[\s._-]+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}
