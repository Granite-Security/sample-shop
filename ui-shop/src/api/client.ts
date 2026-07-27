const BASE = '';

let accessToken: string | null = null;

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
  const headers = createHeaders(fetchOptions.headers);

  if (skipAuth) {
    headers.delete('Authorization');
  }

  const response = await fetch(`${BASE}${path}`, {
    ...fetchOptions,
    headers,
    cache: 'no-store',
  });

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
