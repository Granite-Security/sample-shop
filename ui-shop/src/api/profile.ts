import { request } from './client';
import { storageApi } from './storage';
import type {
  AddressRequest,
  AddressResponse,
  AdminUserProfile,
  ProfileResponse,
  UpdateProfileRequest,
  UserFile,
} from '../types';

export const profileApi = {
  getProfiles: () =>
    request<AdminUserProfile[]>('/api/profiles'),

  getProfileByUsername: (username: string) =>
    request<AdminUserProfile>(`/api/profiles/${encodeURIComponent(username)}`),

  getProfile: () =>
    request<ProfileResponse>('/api/profiles/me'),

  updateProfile: (body: UpdateProfileRequest) =>
    request<ProfileResponse>('/api/profiles/me', { method: 'PUT', body: JSON.stringify(body) }),

  getAddresses: () =>
    request<AddressResponse[]>('/api/profiles/me/addresses'),

  createAddress: (body: AddressRequest) =>
    request<AddressResponse>('/api/profiles/me/addresses', { method: 'POST', body: JSON.stringify(body) }),

  updateAddress: (id: number, body: AddressRequest) =>
    request<AddressResponse>(`/api/profiles/me/addresses/${id}`, { method: 'PUT', body: JSON.stringify(body) }),

  deleteAddress: (id: number) =>
    request<void>(`/api/profiles/me/addresses/${id}`, { method: 'DELETE' }),

  getFiles: () =>
    request<UserFile[]>('/api/profiles/me/files'),

  registerFile: (body: { key: string; url: string; fileName: string; contentType: string; sizeBytes: number }) =>
    request<UserFile>('/api/profiles/me/files', { method: 'POST', body: JSON.stringify(body) }),

  // Upload goes straight to storage (same pattern as the admin product-media
  // upload in storageApi.uploadFile) rather than through a profile-brokered
  // presign — profile only records ownership afterward via registerFile.
  uploadFile: async (file: File): Promise<UserFile> => {
    const presigned = await storageApi.presignUpload(file.name, file.type, 'user-files');
    const putResponse = await fetch(presigned.uploadUrl, {
      method: 'PUT',
      headers: { 'Content-Type': file.type },
      body: file,
    });
    if (!putResponse.ok) {
      throw new Error(`Upload failed: ${putResponse.status} ${putResponse.statusText}`);
    }
    return profileApi.registerFile({
      key: presigned.key,
      url: presigned.publicUrl,
      fileName: file.name,
      contentType: file.type,
      sizeBytes: file.size,
    });
  },

  deleteFile: (id: number) =>
    request<void>(`/api/profiles/me/files/${id}`, { method: 'DELETE' }),
};
