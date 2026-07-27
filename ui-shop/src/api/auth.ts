import { request } from './client';
import type { RegistrationRequest, RegistrationResponse } from '../types';

export const authApi = {
  register: (body: RegistrationRequest) =>
    request<RegistrationResponse>('/auth/api/register', {
      method: 'POST',
      skipAuth: true,
      body: JSON.stringify(body),
    }),
};
