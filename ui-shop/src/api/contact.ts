import { request } from './client';
import type { ContactRequest, ContactResponse } from '../types';

/**
 * The public contact form (docs/users/messaging.md §11).
 *
 * Served by profile under /api/profiles/contact — the one endpoint in that service
 * that does not require a token. The Authorization header is still sent when there
 * is one: that is how the server knows to file the message under the signed-in
 * username instead of the name and email the form collected.
 */
export const contactApi = {
  submit: (body: ContactRequest) =>
    request<ContactResponse>('/api/profiles/contact',
      { method: 'POST', body: JSON.stringify(body) }),
};
