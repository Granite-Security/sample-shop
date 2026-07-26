import { request } from './client';
import type {
  AddressRequest,
  AddressResponse,
  AdminUserProfile,
  ProfileResponse,
  UpdateProfileRequest,
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
};
