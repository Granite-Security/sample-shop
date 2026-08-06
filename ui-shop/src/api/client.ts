const BASE = '';

let accessToken: string | null = null;

/**
 * How to get a fresh access token. Injected by AuthProvider rather than imported,
 * so this module stays free of oidc-client-ts — the same reasoning as
 * setAccessToken below.
 */
let refreshAccessToken: (() => Promise<string | null>) | null = null;

/**
 * One refresh in flight at a time. Without this, a page that fires five requests
 * on mount would start five silent renewals when the token expires, and four of
 * them would race the fifth's session update.
 */
let refreshInFlight: Promise<string | null> | null = null;

export type ApiRequestOptions = RequestInit & {
  skipAuth?: boolean;
};

export class ApiError extends Error {
  status: number;
  data: unknown;

  constructor(status: number, message: string, data: unknown) {
    super(message);
    this.status = status;
    this.data = data;
  }
}

export function setAccessToken(token: string | null) {
  accessToken = token;
}

export function setTokenRefresher(refresher: (() => Promise<string | null>) | null) {
  refreshAccessToken = refresher;
}

function refreshOnce(): Promise<string | null> {
  if (!refreshAccessToken) return Promise.resolve(null);
  if (!refreshInFlight) {
    refreshInFlight = refreshAccessToken()
      .catch(() => null)
      .finally(() => { refreshInFlight = null; });
  }
  return refreshInFlight;
}

function createHeaders(headers?: HeadersInit): Headers {
  const nextHeaders = new Headers(headers);

  if (!nextHeaders.has('Content-Type')) {
    nextHeaders.set('Content-Type', 'application/json');
  }

  if (accessToken) {
    nextHeaders.set('Authorization', `Bearer ${accessToken}`);
  }

  return nextHeaders;
}

export async function request<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const { skipAuth, ...fetchOptions } = options;

  const send = () => {
    const headers = createHeaders(fetchOptions.headers);
    if (skipAuth) {
      headers.delete('Authorization');
    }
    // Headers are rebuilt per attempt so a retry picks up the refreshed token.
    return fetch(`${BASE}${path}`, { ...fetchOptions, headers, cache: 'no-store' });
  };

  let response = await send();

  // Access tokens live ~5 minutes. Without this, a page open longer than that
  // fails every call until the user reloads — the token is stale, not the
  // session. Refresh silently and retry exactly once; a second 401 means the
  // session itself is gone and the caller should see it.
  if (response.status === 401 && !skipAuth && refreshAccessToken) {
    const fresh = await refreshOnce();
    if (fresh) {
      response = await send();
    }
  }

  if (response.status === 204) {
    return undefined as T;
  }

  if (response.status === 401) {
    throw new Error('Unauthorized');
  }

  const data = await response.json();

  if (!response.ok) {
    const message = data.detail ?? data.title ?? response.statusText;
    throw new ApiError(response.status, `[${response.status}] ${message}`, data);
  }

  return data;
}
