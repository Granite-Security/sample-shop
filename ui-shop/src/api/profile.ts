import { request } from './client';
import type {
  AddressRequest,
  AddressResponse,
  AdminUserProfile,
  PresignFileResponse,
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

  presignFile: (fileName: string, contentType: string, sizeBytes: number) =>
    request<PresignFileResponse>('/api/profiles/me/files/presign', {
      method: 'POST',
      body: JSON.stringify({ fileName, contentType, sizeBytes }),
    }),

  registerFile: (body: { key: string; url: string; fileName: string; contentType: string; sizeBytes: number }) =>
    request<UserFile>('/api/profiles/me/files', { method: 'POST', body: JSON.stringify(body) }),

  uploadFile: async (file: File): Promise<UserFile> => {
    const presigned = await profileApi.presignFile(file.name, file.type, file.size);
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
