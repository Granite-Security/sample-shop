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
  HandleAvailability,
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
// Above this, the duplicate check is skipped rather than risking the tab on a
// whole-file ArrayBuffer. 100 MB hashes in well under a second on a laptop and
// is far below what a phone will choke on.
const HASHABLE_MAX_BYTES = 100_000_000;

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
  // Takes a public profile down without touching the account
  // (docs/profile/public-profile.md step 9). Also releases the handle.
  unpublishUser: (username: string) =>
    request<void>(`/api/profiles/admin/users/${encodeURIComponent(username)}/unpublish`,
      { method: 'POST' }),

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

  // Handle and visibility. Separate from updateProfile for the same reason the
  // avatar is, plus a 409 of their own (docs/profile/public-profile.md D5).
  setHandle: (handle: string) =>
    request<ProfileResponse>('/api/profiles/me/handle',
      { method: 'PUT', body: JSON.stringify({ handle }) }),

  setVisibility: (publicProfile: boolean) =>
    request<ProfileResponse>('/api/profiles/me/visibility',
      { method: 'PUT', body: JSON.stringify({ publicProfile }) }),

  // Authenticated on purpose: an anonymous availability check is a free
  // enumeration oracle over the whole handle namespace.
  checkHandle: (handle: string) =>
    request<HandleAvailability>(
      `/api/profiles/me/handle/available?handle=${encodeURIComponent(handle)}`),

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
    key: string; url: string; fileName: string; contentType: string; sizeBytes: number;
    contentHash: string | null;
  }) =>
    request<UserFile>('/api/profiles/me/files', { method: 'POST', body: JSON.stringify(body) }),

  // Upload goes straight to storage (same pattern as the admin product-media
  // upload in storageApi.uploadFile) rather than through a profile-brokered
  // presign — profile only records ownership afterward via registerFile.
  uploadFile: async (file: File): Promise<UserFile> => {
    // sha256Hex reads the whole file into memory — crypto.subtle.digest has no
    // streaming form — so hashing a large video would hang or crash the tab.
    // Above the threshold the file goes up unhashed and simply is not
    // de-duplicated; the backend accepts a null hash, and migration 004 already
    // made null hashes non-colliding in the unique index.
    const contentHash = file.size > HASHABLE_MAX_BYTES ? null : await sha256Hex(file);

    if (contentHash) {
      const dup = await profileApi.checkDuplicateFile(contentHash);
      if (dup.duplicate && dup.existingFile) {
        throw new DuplicateFileError(dup.existingFile);
      }
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

  // "Publish to profile". A flag on the file, not a URL copied onto the profile,
  // so deleting the file takes it off the public page by itself.
  setFileShared: (id: number, shared: boolean) =>
    request<UserFile>(`/api/profiles/me/files/${id}/share`,
      { method: 'PUT', body: JSON.stringify({ shared }) }),
};
