import { request } from './client';
import { storageApi } from './storage';
import { downscaleToSquare } from '../utils/avatar';
import type {
  AddressRequest,
  AddressResponse,
  AdminUserProfile,
  AdminUserView,
  AvatarSource,
  DeleteUserResult,
  DuplicateFileCheckResponse,
  ProfileResponse,
  UpdateProfileRequest,
  UserFile,
} from '../types';

export class DuplicateFileError extends Error {
  existingFile: UserFile;

  constructor(existingFile: UserFile) {
    super('This file has already been uploaded.');
    this.existingFile = existingFile;
  }
}

// Hashed locally so a duplicate can be detected — and the upload skipped
// entirely — before any bytes are sent, rather than discovering it only
// after uploading a full copy.
async function sha256Hex(file: File): Promise<string> {
  const buffer = await file.arrayBuffer();
  const digest = await crypto.subtle.digest('SHA-256', buffer);
  return Array.from(new Uint8Array(digest))
    .map(b => b.toString(16).padStart(2, '0'))
    .join('');
}

export const profileApi = {
  // The admin users list. Note this is NOT getProfiles() below — profiles are
  // a different set from users (docs/users/blocking-users.md §2.1, D3).
  getAdminUsers: () =>
    request<AdminUserView[]>('/api/profiles/admin/users'),

  blockUser: (username: string) =>
    request<AdminUserView>(`/api/profiles/admin/users/${encodeURIComponent(username)}/block`,
      { method: 'POST' }),

  unblockUser: (username: string) =>
    request<AdminUserView>(`/api/profiles/admin/users/${encodeURIComponent(username)}/unblock`,
      { method: 'POST' }),

  // Returns an outcome rather than throwing when the user cannot be deleted:
  // BLOCKED_INSTEAD is a success, and the caller has to say so.
  deleteUser: (username: string) =>
    request<DeleteUserResult>(`/api/profiles/admin/users/${encodeURIComponent(username)}`,
      { method: 'DELETE' }),

  getProfiles: () =>
    request<AdminUserProfile[]>('/api/profiles'),

  getProfileByUsername: (username: string) =>
    request<AdminUserProfile>(`/api/profiles/${encodeURIComponent(username)}`),

  getProfile: () =>
    request<ProfileResponse>('/api/profiles/me'),

  updateProfile: (body: UpdateProfileRequest) =>
    request<ProfileResponse>('/api/profiles/me', { method: 'PUT', body: JSON.stringify(body) }),

  // Avatar. Deliberately separate from updateProfile, which overwrites every
  // field it is given (docs/users/user-pic.md D4).
  uploadAvatar: async (file: File): Promise<ProfileResponse> => {
    const square = await downscaleToSquare(file);
    const presigned = await storageApi.presignUpload(square.name, square.type, 'avatars');
    const putResponse = await fetch(presigned.uploadUrl, {
      method: 'PUT',
      headers: { 'Content-Type': square.type },
      body: square,
    });
    if (!putResponse.ok) {
      throw new Error(`Upload failed: ${putResponse.status} ${putResponse.statusText}`);
    }
    return request<ProfileResponse>('/api/profiles/me/avatar', {
      method: 'PUT',
      body: JSON.stringify({
        key: presigned.key,
        url: presigned.publicUrl,
        contentType: square.type,
        sizeBytes: square.size,
      }),
    });
  },

  setAvatarSource: (source: AvatarSource) =>
    request<ProfileResponse>('/api/profiles/me/avatar/source',
      { method: 'PUT', body: JSON.stringify({ source }) }),

  removeAvatar: () =>
    request<ProfileResponse>('/api/profiles/me/avatar', { method: 'DELETE' }),

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

  checkDuplicateFile: (contentHash: string) =>
    request<DuplicateFileCheckResponse>(`/api/profiles/me/files/duplicate?hash=${encodeURIComponent(contentHash)}`),

  registerFile: (body: {
    key: string; url: string; fileName: string; contentType: string; sizeBytes: number; contentHash: string;
  }) =>
    request<UserFile>('/api/profiles/me/files', { method: 'POST', body: JSON.stringify(body) }),

  // Upload goes straight to storage (same pattern as the admin product-media
  // upload in storageApi.uploadFile) rather than through a profile-brokered
  // presign — profile only records ownership afterward via registerFile.
  uploadFile: async (file: File): Promise<UserFile> => {
    const contentHash = await sha256Hex(file);

    const dup = await profileApi.checkDuplicateFile(contentHash);
    if (dup.duplicate && dup.existingFile) {
      throw new DuplicateFileError(dup.existingFile);
    }

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
      contentHash,
    });
  },

  deleteFile: (id: number) =>
    request<void>(`/api/profiles/me/files/${id}`, { method: 'DELETE' }),
};
