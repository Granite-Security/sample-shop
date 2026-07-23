import { request } from './client';

export const shopApi = {
  greetings: () =>
    request<{ message: string }>('/api/shop/greetings'),
};
