import { request } from './client';
import type { MediaItem, PresignResponse } from '../types';

export const storageApi = {
  presignUpload: (fileName: string, contentType: string, scope = 'products') =>
    request<PresignResponse>('/api/storage/presign', {
      method: 'POST',
      body: JSON.stringify({ fileName, contentType, scope }),
    }),

  uploadFile: async (file: File, scope = 'products'): Promise<MediaItem> => {
    const presigned = await storageApi.presignUpload(file.name, file.type, scope);
    const putResponse = await fetch(presigned.uploadUrl, {
      method: 'PUT',
      headers: { 'Content-Type': file.type },
      body: file,
    });
    if (!putResponse.ok) {
      throw new Error(`Upload failed: ${putResponse.status} ${putResponse.statusText}`);
    }
    // Never auto-default a freshly uploaded image — the admin must pick one
    // explicitly, so the storefront thumbnail never changes as a surprise.
    return { key: presigned.key, url: presigned.publicUrl, contentType: file.type, isDefault: false };
  },

  deleteObject: (key: string) =>
    request<void>('/api/storage/objects', { method: 'DELETE', body: JSON.stringify({ key }) }),
};
