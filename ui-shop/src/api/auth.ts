import { request } from './client';
import type { RegistrationRequest, RegistrationResponse } from '../types';

export const authApi = {
  register: (body: RegistrationRequest) =>
    request<RegistrationResponse>('/auth/api/register', {
      method: 'POST',
      skipAuth: true,
      body: JSON.stringify(body),
    }),

  requestPasswordReset: (email: string) =>
    request<void>('/auth/api/password-reset/request', {
      method: 'POST',
      skipAuth: true,
      body: JSON.stringify({ email }),
    }),

  confirmPasswordReset: (token: string, newPassword: string) =>
    request<void>('/auth/api/password-reset/confirm', {
      method: 'POST',
      skipAuth: true,
      body: JSON.stringify({ token, newPassword }),
    }),
};
