import { request } from './client';
import type { PresignResponse } from '../types';

export const storageApi = {
  presignUpload: (fileName: string, contentType: string, scope = 'products') =>
    request<PresignResponse>('/api/storage/presign', {
      method: 'POST',
      body: JSON.stringify({ fileName, contentType, scope }),
    }),

  uploadFile: async (file: File, scope = 'products') => {
    const presigned = await storageApi.presignUpload(file.name, file.type, scope);
    const putResponse = await fetch(presigned.uploadUrl, {
      method: 'PUT',
      headers: { 'Content-Type': file.type },
      body: file,
    });
    if (!putResponse.ok) {
      throw new Error(`Upload failed: ${putResponse.status} ${putResponse.statusText}`);
    }
    return { key: presigned.key, url: presigned.publicUrl, contentType: file.type };
  },

  deleteObject: (key: string) =>
    request<void>('/api/storage/objects', { method: 'DELETE', body: JSON.stringify({ key }) }),
};
