// fetch wrapper: same-origin absolute URLs, bearer token, JSON, RFC 9457 problem details.

import { auth } from "./session";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly code: string | null = null,
    readonly extra: Record<string, unknown> = {},
  ) {
    super(message);
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  const token = auth.session()?.token;
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const res = await fetch(new URL(path, window.location.origin), {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 401 && token) auth.signOut(); // expired or revoked
  if (!res.ok) {
    let problem: Record<string, unknown> = {};
    try {
      problem = (await res.json()) as Record<string, unknown>;
    } catch {
      // not JSON
    }
    const message = typeof problem.detail === "string" ? problem.detail : `${res.status} ${res.statusText}`.trim();
    throw new ApiError(res.status, message, typeof problem.code === "string" ? problem.code : null, problem);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const http = {
  get: <T>(path: string) => request<T>("GET", path),
  post: <T>(path: string, body?: unknown) => request<T>("POST", path, body),
  del: (path: string) => request<void>("DELETE", path),
};
