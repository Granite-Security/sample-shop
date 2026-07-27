import { request } from './client';

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

export const accountApi = {
  // Targets /auth, not /api — separate module for that reason. Uses the same
  // `request` helper as everything else, so the Bearer token is attached the
  // same way.
  changePassword: (body: ChangePasswordRequest) =>
    request<void>('/auth/api/me/password', { method: 'PUT', body: JSON.stringify(body) }),
};
