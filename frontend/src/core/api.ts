import { t } from './i18n';
/** One place for every HTTP call: attaches the token and turns error bodies into ApiError. */

const TOKEN_KEY = 'endustry.token';

export class ApiError extends Error {
  status: number;
  code: string;
  fieldErrors: Record<string, string>;
  details: unknown;

  constructor(status: number, code: string, message: string, fieldErrors?: Record<string, string>, details?: unknown) {
    super(t(message));
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors ?? {};
    this.details = details;
  }
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export const tokenStore = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (token: string) => localStorage.setItem(TOKEN_KEY, token),
  clear: () => localStorage.removeItem(TOKEN_KEY),
};

let onUnauthenticated: () => void = () => {};
let onModuleDisabled: () => void = () => {};

/** Lets the auth layer react when the server says the session is gone. */
export function setUnauthenticatedHandler(handler: () => void) {
  onUnauthenticated = handler;
}

/** Lets the auth layer refresh what is switched on when the server says a module was switched off. */
export function setModuleDisabledHandler(handler: () => void) {
  onModuleDisabled = handler;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const token = tokenStore.get();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  let response: Response;
  try {
    response = await fetch(`/api${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw new ApiError(0, 'NETWORK', 'Cannot reach the server. Check that it is running and try again.');
  }
  const text = await response.text();
  const data = text ? safeParse(text) : null;
  if (!response.ok) {
    // A lost session, or a client suspended by the provider, sends the user back to sign-in.
    const signingIn = path.startsWith('/auth/login');
    if (!signingIn && (response.status === 401 || data?.code === 'TENANT_SUSPENDED')) onUnauthenticated();
    if (data?.code === 'MODULE_DISABLED') onModuleDisabled();
    throw new ApiError(
      response.status,
      data?.code ?? 'ERROR',
      data?.message ?? `Request failed (${response.status})`,
      data?.fieldErrors,
      data?.details,
    );
  }
  return data as T;
}

function safeParse(text: string) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/** Builds "?a=1&b=2", leaving out empty values. */
export function query(params: Record<string, unknown>): string {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') search.set(key, String(value));
  });
  const text = search.toString();
  return text ? `?${text}` : '';
}

/** Fetches a file such as a printed PDF, keeping the server's file name. Errors arrive as ApiError. */
async function file(method: string, path: string, body?: unknown): Promise<{ blob: Blob; fileName: string | null }> {
  const headers: Record<string, string> = {};
  const token = tokenStore.get();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let response: Response;
  try {
    response = await fetch(`/api${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw new ApiError(0, 'NETWORK', 'Cannot reach the server. Check that it is running and try again.');
  }
  if (!response.ok) {
    const data = safeParse(await response.text());
    if (response.status === 401) onUnauthenticated();
    throw new ApiError(response.status, data?.code ?? 'ERROR', data?.message ?? `Request failed (${response.status})`, data?.fieldErrors, data?.details);
  }
  const disposition = response.headers.get('Content-Disposition') ?? '';
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition);
  return { blob: await response.blob(), fileName: match ? decodeURIComponent(match[1]) : null };
}

export const api = {
  file: (path: string) => file('GET', path),
  postFile: (path: string, body?: unknown) => file('POST', path, body),
  get: <T = any>(path: string) => request<T>('GET', path),
  post: <T = any>(path: string, body?: unknown) => request<T>('POST', path, body),
  put: <T = any>(path: string, body?: unknown) => request<T>('PUT', path, body),
  delete: <T = any>(path: string) => request<T>('DELETE', path),
};
