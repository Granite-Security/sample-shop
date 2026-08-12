import { request } from './client';
import type { PublicFile, PublicProfileResponse } from '../types';

/**
 * The one API call in this app that works with no session at all
 * (docs/profile/public-profile.md step 6).
 *
 * `skipAuth` is deliberate: the endpoint is permitAll, so sending a token buys
 * nothing, and skipping it also keeps the client's 401-refresh-and-retry path
 * from firing for a visitor who has no session to refresh.
 *
 * A 404 covers both "no such handle" and "not published" — the server does not
 * distinguish them, and neither should the page.
 */
export const publicProfileApi = {
  get: (handle: string) =>
    request<PublicProfileResponse>(`/api/profiles/public/${encodeURIComponent(handle)}`,
      { skipAuth: true }),

  files: (handle: string) =>
    request<PublicFile[]>(`/api/profiles/public/${encodeURIComponent(handle)}/files`,
      { skipAuth: true }),
};
