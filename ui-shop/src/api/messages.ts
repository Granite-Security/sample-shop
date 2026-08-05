import { request } from './client';
import type { MessageResponse, RecipientResponse, SendMessageRequest } from '../types';

/**
 * User-to-user messaging (docs/users/messaging.md).
 *
 * Served by profile under /api/profiles/me/messages, so every call is already
 * authenticated by the same rule as the rest of the account pages.
 */
export const messagesApi = {
  list: (box: 'inbox' | 'sent' = 'inbox') =>
    request<MessageResponse[]>(`/api/profiles/me/messages?box=${box}`),

  get: (id: number) =>
    request<MessageResponse>(`/api/profiles/me/messages/${id}`),

  unreadCount: () =>
    request<{ count: number }>('/api/profiles/me/messages/unread-count'),

  send: (body: SendMessageRequest) =>
    request<MessageResponse>('/api/profiles/me/messages',
      { method: 'POST', body: JSON.stringify(body) }),

  markRead: (id: number) =>
    request<void>(`/api/profiles/me/messages/${id}/read`, { method: 'POST' }),

  remove: (id: number) =>
    request<void>(`/api/profiles/me/messages/${id}`, { method: 'DELETE' }),

  // Returns nothing for queries under 2 characters — the server enforces that,
  // so a stray single keystroke cannot enumerate the user table.
  searchRecipients: (q: string) =>
    request<RecipientResponse[]>(`/api/profiles/me/messages/recipients?q=${encodeURIComponent(q)}`),
};
